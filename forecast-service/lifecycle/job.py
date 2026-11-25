"""One training job: lock -> record RUNNING -> load real history -> train.main (unchanged gate) -> record.

Run as a separate process by the lifecycle manager so that training never blocks predictions:
    python -m lifecycle.job --trigger INITIAL --attempt 1

Safety:
* holds the Postgres advisory lock for the whole job; a second job exits immediately (no run recorded)
* every outcome is written to ml_training_runs, including crashes inside training (FAILED + error class)
* if the process is killed, the lock is released by Postgres and the RUNNING row is later marked INTERRUPTED
* promotion happens only inside train.main, through the existing gate and registry (atomic pointer write)
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from datetime import timedelta
from pathlib import Path

from lifecycle.config import LifecycleConfig
from lifecycle.repository import PostgresRepository, Repository, utcnow

log = logging.getLogger("vayudrishti.lifecycle.job")

STATUS_MAP = {"PROMOTED": "PROMOTED", "EVALUATED_NOT_PROMOTED": "NOT_PROMOTED", "INSUFFICIENT_DATA": "INSUFFICIENT_DATA"}


def train_args(cfg: LifecycleConfig, model_dir: Path) -> list[str]:
    return ["--out", str(model_dir), "--models", cfg.models, "--min-stations", str(cfg.min_stations),
            "--origin-stride", str(cfg.origin_stride), "--max-train-rows", str(cfg.max_train_rows)]


def run_job(repo: Repository, cfg: LifecycleConfig, model_dir: Path, trigger: str, attempt: int,
            extra_args: list[str] | None = None, now=None) -> dict:
    if not repo.try_lock():
        log.info("Another training job holds the lock; exiting without starting")
        return {"status": "LOCKED"}
    try:
        run_id = repo.insert_run(trigger, attempt)
        try:
            from train import dataset_fingerprint, main as train_main
            raw = repo.load_history((now or utcnow()) - timedelta(days=cfg.history_days))
            fields = {"observations": int(len(raw)), "stations": int(raw["station_id"].nunique()) if len(raw) else 0}
            if len(raw):
                fields.update(dataset_fingerprint=dataset_fingerprint(raw),
                              data_first_ts=raw["ts"].min().to_pydatetime(), data_last_ts=raw["ts"].max().to_pydatetime())
            report = train_main([*train_args(cfg, model_dir), *(extra_args or [])], raw=raw,
                                data_label="postgres:station_aqi")
        except Exception as e:   # training or evaluation failed: record it, never promote
            log.exception("Training run %s failed", run_id)
            repo.finish_run(run_id, status="FAILED", error=f"{type(e).__name__}: {str(e)[:500]}",
                            **(fields if "fields" in locals() else {}))
            repo.add_event("TRAINING_FAILED", f"run {run_id} failed: {type(e).__name__}", {"run_id": run_id})
            return {"status": "FAILED", "run_id": run_id}

        status = STATUS_MAP.get(report.get("status"), "FAILED")
        reasons = report.get("promotion", {}).get("reasons") or []
        if status == "INSUFFICIENT_DATA":
            reasons = [report.get("readiness", {}).get("reason", "insufficient data")]
        repo.finish_run(run_id, status=status, model_version=report.get("version"), model_name=report.get("model_name"),
                        metrics=report.get("test"), reasons="; ".join(reasons) or None, **fields)
        if status == "PROMOTED":
            repo.add_event("PROMOTION", f"promoted {report['version']}",
                           {"run_id": run_id, "test_mae": report["test"]["model_mae"],
                            "persistence_mae": report["test"]["baselines"]["persistence"]["mae"]})
        else:
            repo.add_event("TRAINING_COMPLETED", f"run {run_id}: {status}", {"run_id": run_id, "reasons": reasons})
        return {"status": status, "run_id": run_id, "version": report.get("version")}
    finally:
        repo.unlock()


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    p = argparse.ArgumentParser()
    p.add_argument("--trigger", required=True)
    p.add_argument("--attempt", type=int, default=1)
    p.add_argument("--model-dir", default=os.getenv("MODEL_DIR", str(Path(__file__).resolve().parent.parent / "models" / "artifacts")))
    args = p.parse_args(argv)
    result = run_job(PostgresRepository(), LifecycleConfig.from_env(), Path(args.model_dir), args.trigger, args.attempt)
    log.info("Training job finished: %s", result)
    return 0 if result["status"] != "FAILED" else 1


if __name__ == "__main__":
    sys.exit(main())
