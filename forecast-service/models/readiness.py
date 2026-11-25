"""Training-data readiness. Refuses to train on too little history and says exactly what is missing.

The 24-hour minimum used by the API only decides whether a *forecast* can be served. Training and
honestly evaluating a model needs far more: a feature warm-up (the longest lag/seasonal look-back),
a training window, a validation window for model selection and interval calibration, and a separate
recent test window that nothing is tuned on.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import pandas as pd


@dataclass(frozen=True)
class SplitPlan:
    """Window lengths in grid steps (hours for hourly data, days for daily data)."""
    warmup_steps: int = 168      # longest look-back in the hourly features (lag_167, 7-day same-hour mean)
    min_train_steps: int = 168
    val_steps: int = 168
    test_steps: int = 168
    min_stations: int = 1
    min_coverage: float = 0.5    # a station counts if it has valid data in >= this share of the span

    @property
    def required_steps(self) -> int:
        return self.warmup_steps + self.min_train_steps + self.val_steps + self.test_steps


HOURLY_PLAN = SplitPlan()
DAILY_PLAN = SplitPlan(warmup_steps=28, min_train_steps=90, val_steps=30, test_steps=30)


@dataclass
class Readiness:
    ready: bool
    span_steps: int
    required_steps: int
    unit: str
    stations_total: int
    stations_usable: int
    first_ts: str | None
    last_ts: str | None
    valid_station_hours: int
    reason: str
    plan: dict


def assess(regular: pd.DataFrame, plan: SplitPlan, step: pd.Timedelta = pd.Timedelta(hours=1),
           unit: str = "h") -> Readiness:
    """``regular``: output of features.cleaning.regularize (station_id, ts, aqi, imputed, gap)."""
    valid = regular[regular["aqi"].notna() & ~regular["imputed"].astype(bool)]
    if valid.empty:
        return Readiness(False, 0, plan.required_steps, unit, 0, 0, None, None, 0,
                         "no valid observations", asdict(plan))
    first, last = valid["ts"].min(), valid["ts"].max()
    span = int((last - first) / step) + 1
    per_station = valid.groupby("station_id").size()
    usable = int((per_station >= plan.min_coverage * span).sum())
    reasons = []
    if span < plan.required_steps:
        missing = plan.required_steps - span
        days = missing / 24 if unit == "h" else missing
        reasons.append(f"history spans {span} {unit}; need {plan.required_steps} {unit} "
                       f"({plan.warmup_steps} warm-up + {plan.min_train_steps} train + {plan.val_steps} validation "
                       f"+ {plan.test_steps} test), i.e. about {days:.1f} more days of continuous data")
    if usable < plan.min_stations:
        reasons.append(f"{usable} stations have data for >= {plan.min_coverage:.0%} of the span; need {plan.min_stations}")
    return Readiness(not reasons, span, plan.required_steps, unit, int(per_station.size), usable,
                     str(first), str(last), int(len(valid)), "; ".join(reasons) or "ready", asdict(plan))
