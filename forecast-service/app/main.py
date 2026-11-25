"""VayuDrishti forecast + advice service (internal; called only by the Java backend).

Endpoints: POST /forecast, POST /advice, GET /health, GET /model-info, GET /lifecycle.

When LIFECYCLE_ENABLED=true and the database is configured (PG* variables), an automated ML lifecycle loop
runs in a background thread (readiness monitoring, scheduled training in a separate process, promotion via
the existing gate, live monitoring and rollback). See lifecycle/manager.py and docs/ml-forecasting.md.
"""
from __future__ import annotations

import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from advice.phrasing import GroqPhraser, Phraser
from advice.rules import load_rules
from advice.service import make_advice
from app.config import Settings
from app.forecaster import make_forecast
from app.model_store import ModelStore
from app.schemas import AdviceRequest, AdviceResponse, ForecastRequest, ForecastResponse
from lifecycle.config import LifecycleConfig, database_configured
from lifecycle.manager import LifecycleManager, PredictionStats, start_background

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("saanscheck.forecast")


def create_app(settings: Settings | None = None, phraser: Phraser | None = None,
               use_llm: bool = True, lifecycle: LifecycleManager | None = None,
               start_lifecycle_loop: bool = True) -> FastAPI:
    settings = settings or Settings.from_env()
    store = ModelStore(settings.model_dir)
    rules = load_rules(settings.rules_file)
    stats = lifecycle.stats if lifecycle is not None else PredictionStats()
    if phraser is None and use_llm and settings.groq_api_key:
        try:
            phraser = GroqPhraser(settings.groq_api_key, settings.groq_model, settings.llm_timeout_s)
        except Exception as e:   # missing SDK or bad config: run template-only
            log.warning("LLM disabled: %s", e)

    if lifecycle is None:
        cfg = LifecycleConfig.from_env()
        if cfg.enabled and database_configured():
            from lifecycle.repository import PostgresRepository
            lifecycle = LifecycleManager(PostgresRepository(), settings.model_dir, cfg, store=store, stats=stats)
        elif cfg.enabled:
            log.warning("LIFECYCLE_ENABLED but no database configured (PGHOST); lifecycle disabled")
    elif lifecycle.store is None:
        lifecycle.store = store

    stop = threading.Event()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if lifecycle is not None and start_lifecycle_loop:
            start_background(lifecycle, stop)
        yield
        stop.set()

    app = FastAPI(title="VayuDrishti forecast & advice service", version="0.1.0", lifespan=lifespan)
    app.state.store = store
    app.state.lifecycle = lifecycle
    app.state.prediction_stats = stats

    @app.get("/health")
    def health() -> dict:
        return {"status": "ok", "model_loaded": store.model is not None, "llm_configured": phraser is not None,
                "lifecycle_enabled": lifecycle is not None}

    @app.get("/model-info")
    def model_info() -> dict:
        info = store.info()
        if lifecycle is not None:
            try:
                s = lifecycle.status(runs=5, events=0)
                info["lifecycle"] = {k: s[k] for k in ("forecasting_method", "training_running",
                                                       "last_successful_training", "next_readiness_check")}
                info["lifecycle"]["readiness"] = {k: (s["readiness"] or {}).get(k)
                                                  for k in ("ready", "reason", "span_hours", "required_hours")}
            except Exception as e:   # lifecycle storage unavailable must not break model-info
                info["lifecycle"] = {"error": type(e).__name__}
        else:
            info["lifecycle"] = {"enabled": False}
        return info

    @app.get("/lifecycle")
    def lifecycle_status() -> dict:
        if lifecycle is None:
            return {"enabled": False}
        try:
            return lifecycle.status()
        except Exception as e:
            raise HTTPException(status_code=503, detail=f"lifecycle storage unavailable: {type(e).__name__}") from e

    @app.post("/forecast", response_model=ForecastResponse)
    def forecast(req: ForecastRequest) -> ForecastResponse:
        try:
            resp = make_forecast(req, store, min_points=settings.min_history_points)
        except ValueError as e:          # invalid or insufficient input
            raise HTTPException(status_code=422, detail=str(e)) from e
        except Exception as e:           # model inference failed: report it, never substitute a fake forecast
            stats.record(False)
            log.exception("Prediction failed for %s", req.station_id)
            raise HTTPException(status_code=503, detail=f"model prediction failed: {type(e).__name__}") from e
        if resp.method == "MODEL":
            stats.record(True)
        return resp

    @app.post("/advice", response_model=AdviceResponse)
    def advice(req: AdviceRequest) -> AdviceResponse:
        return make_advice(req, rules, phraser)

    return app


app = create_app()
