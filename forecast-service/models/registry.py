"""Model registry: versioned artifacts, the active-model pointer, promotion history and rollback.

Layout under the artifacts directory:
    <version>/model.joblib      trained model (RandomForestModel or QuantileModel)
    <version>/metadata.json     training data, splits, metrics, intervals, per-station baseline fallback
    <version>/eval_*.csv        evaluation tables
    current.json                metadata of the ACTIVE model (+ "model_file"), read by the API service
    history.json                every promotion / rollback / activation, newest last

A model is only written to current.json by ``promote`` after train.py's evaluation gate passed, or by an
explicit ``activate``/``rollback`` of a version that previously passed it. Deleting current.json returns
the service to the persistence baseline.

CLI:
    python -m models.registry list
    python -m models.registry rollback            # back to the previously active version
    python -m models.registry activate <version>  # any version that passed evaluation
    python -m models.registry deactivate          # serve the persistence baseline
"""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_DIR = Path(__file__).resolve().parent / "artifacts"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _write_atomic(path: Path, data) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
    os.replace(tmp, path)   # readers never see a half-written pointer


class Registry:
    def __init__(self, root: Path = DEFAULT_DIR):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    # ---- reading ---------------------------------------------------------------------------
    def active(self) -> dict | None:
        p = self.root / "current.json"
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None

    def history(self) -> list[dict]:
        p = self.root / "history.json"
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else []

    def metadata(self, version: str) -> dict:
        p = self.root / version / "metadata.json"
        if not p.exists():
            raise KeyError(f"unknown model version {version}")
        return json.loads(p.read_text(encoding="utf-8"))

    def versions(self) -> list[dict]:
        out = []
        for d in sorted(self.root.iterdir()):
            meta = d / "metadata.json"
            if d.is_dir() and meta.exists():
                m = json.loads(meta.read_text(encoding="utf-8"))
                out.append({"version": m["version"], "model_name": m.get("model_name"),
                            "trained_at": m.get("trained_at"), "passed_gate": m.get("promotion", {}).get("passed"),
                            "test_mae": m.get("evaluation", {}).get("test", {}).get("model_mae")})
        return out

    # ---- writing ---------------------------------------------------------------------------
    def version_dir(self, version: str) -> Path:
        d = self.root / version
        d.mkdir(parents=True, exist_ok=True)
        return d

    def save_candidate(self, version: str, metadata: dict) -> None:
        _write_atomic(self.version_dir(version) / "metadata.json", metadata)

    def _set_active(self, version: str, action: str, reason: str) -> dict:
        meta = self.metadata(version)
        if not meta.get("promotion", {}).get("passed"):
            raise ValueError(f"{version} did not pass the evaluation gate and cannot be activated")
        previous = (self.active() or {}).get("version")
        pointer = {**meta, "model_file": f"{version}/model.joblib", "activated_at": _now()}
        _write_atomic(self.root / "current.json", pointer)
        hist = self.history()
        hist.append({"at": _now(), "action": action, "version": version, "previous": previous, "reason": reason})
        _write_atomic(self.root / "history.json", hist)
        return pointer

    def promote(self, version: str, reason: str) -> dict:
        return self._set_active(version, "promote", reason)

    def activate(self, version: str, reason: str = "manual activation") -> dict:
        return self._set_active(version, "activate", reason)

    def rollback(self) -> dict:
        """Re-activate the version that was active before the current one (never the current one itself,
        e.g. after a re-activation of the same version)."""
        current = (self.active() or {}).get("version")
        for entry in reversed(self.history()):
            prev = entry.get("previous")
            if entry["version"] == current and prev and prev != current:
                return self._set_active(prev, "rollback", f"rollback from {current}")
        raise ValueError("no previous model to roll back to")

    def deactivate(self, reason: str = "manual deactivation") -> None:
        current = (self.active() or {}).get("version")
        p = self.root / "current.json"
        if p.exists():
            p.unlink()
        hist = self.history()
        hist.append({"at": _now(), "action": "deactivate", "version": None, "previous": current, "reason": reason})
        _write_atomic(self.root / "history.json", hist)


def main(argv=None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["list", "rollback", "activate", "deactivate"])
    p.add_argument("version", nargs="?")
    p.add_argument("--dir", default=str(DEFAULT_DIR))
    args = p.parse_args(argv)
    reg = Registry(Path(args.dir))
    if args.command == "list":
        active = (reg.active() or {}).get("version")
        for v in reg.versions():
            mark = "*" if v["version"] == active else " "
            print(f"{mark} {v['version']:<40} {v['model_name'] or '-':<14} gate={v['passed_gate']} test_mae={v['test_mae']}")
        print(f"active: {active or 'none (persistence baseline)'}")
    elif args.command == "rollback":
        print("active:", reg.rollback()["version"])
    elif args.command == "activate":
        if not args.version:
            p.error("activate needs a version")
        print("active:", reg.activate(args.version)["version"])
    else:
        reg.deactivate()
        print("active: none (persistence baseline)")


if __name__ == "__main__":
    main()
