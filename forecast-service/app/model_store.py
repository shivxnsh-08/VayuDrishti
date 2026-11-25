"""Loads the active model named in ``<model_dir>/current.json`` (written by models.registry).

* No model is a valid state: the service then serves baselines and says so in /model-info.
* The pointer is re-checked (file mtime) on every request, so a promotion, rollback or activation done
  with ``python -m models.registry`` takes effect without restarting the service.
* If the pointed-to artifact cannot be loaded, the store keeps serving the previous model (or baselines)
  and reports the error rather than failing requests.
"""
from __future__ import annotations

import json
import logging
import threading
from pathlib import Path

import joblib

log = logging.getLogger(__name__)


class ModelStore:
    def __init__(self, model_dir: Path):
        self.model_dir = Path(model_dir)
        self.model = None
        self.metadata: dict = {}
        self.load_error: str | None = None
        self._mtime: float | None = None
        self._lock = threading.Lock()
        self.refresh()

    @property
    def pointer(self) -> Path:
        return self.model_dir / "current.json"

    def refresh(self) -> None:
        """Reload if current.json appeared, disappeared or changed since the last load."""
        mtime = self.pointer.stat().st_mtime if self.pointer.exists() else None
        if mtime == self._mtime:
            return
        with self._lock:
            if mtime == self._mtime:
                return
            self._load(mtime)

    # kept for callers/tests that force a reload
    def load(self) -> None:
        with self._lock:
            self._load(self.pointer.stat().st_mtime if self.pointer.exists() else None)

    def _load(self, mtime: float | None) -> None:
        self._mtime = mtime
        if mtime is None:
            log.warning("No active model in %s; serving baselines only", self.model_dir)
            self.model, self.metadata, self.load_error = None, {}, None
            return
        try:
            meta = json.loads(self.pointer.read_text(encoding="utf-8"))
            if meta.get("freq") != "hourly":
                # A daily (cold-start) model cannot answer hourly requests; keep it for evaluation only.
                log.warning("Active model is %s, not hourly; serving baselines", meta.get("freq"))
                self.model, self.metadata, self.load_error = None, meta, None
                return
            model = joblib.load(self.model_dir / meta["model_file"])
            self.model, self.metadata, self.load_error = model, meta, None
            log.info("Loaded model %s", meta.get("version"))
        except Exception as e:   # keep serving what we had; surface the problem in /model-info
            self.load_error = f"{type(e).__name__}: {e}"
            log.error("Could not load active model: %s", self.load_error)

    @property
    def version(self) -> str | None:
        return self.metadata.get("version") if self.model is not None else None

    def baseline_for(self, station_id: str) -> str | None:
        """Baseline name if the model did not beat the best baseline at this station on the test window."""
        return self.metadata.get("use_baseline", {}).get(station_id)

    def info(self) -> dict:
        self.refresh()
        if not self.metadata:
            out = {"model_loaded": False, "serving": "BASELINE_PERSISTENCE",
                   "note": "No trained model promoted yet; forecasts use the persistence baseline."}
            if self.load_error:
                out["load_error"] = self.load_error
            return out
        ev = self.metadata.get("evaluation", {}).get("test", {})
        return {
            "model_loaded": self.model is not None,
            "version": self.metadata.get("version"),
            "model_name": self.metadata.get("model_name", "lightgbm"),
            "freq": self.metadata.get("freq"),
            "trained_at": self.metadata.get("trained_at"),
            "activated_at": self.metadata.get("activated_at"),
            "training_window": self.metadata.get("training_window"),
            "splits": self.metadata.get("splits"),
            "n_stations": self.metadata.get("n_stations"),
            "horizons": self.metadata.get("horizons"),
            "validation": self.metadata.get("validation"),
            "test_by_horizon": ev.get("by_horizon"),
            "intervals": self.metadata.get("intervals"),
            "stations_served_by_baseline": self.metadata.get("use_baseline", {}),
            "load_error": self.load_error,
        }
