"""Rolling-origin (time-based) backtesting. Never shuffles.

Fold i tests a contiguous block of origin times; its model is trained only on rows whose
*target* time is at or before the first test origin, i.e. on information that existed then.
"""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

from features.build import FreqConfig
from models.baselines import baseline_predictions
from models.lgbm import QuantileModel, feature_columns


@dataclass(frozen=True)
class Fold:
    index: int
    test_start: pd.Timestamp
    test_end: pd.Timestamp     # inclusive


def rolling_origin_folds(origins: pd.Series, n_folds: int, test_size: int) -> list[Fold]:
    """Last ``n_folds`` blocks of ``test_size`` distinct origin times, oldest first."""
    unique = pd.Index(sorted(pd.unique(origins)))
    needed = n_folds * test_size
    if len(unique) <= needed:
        raise ValueError(f"need more than {needed} origin times for {n_folds} folds of {test_size}; have {len(unique)}")
    folds = []
    for i in range(n_folds):
        start = len(unique) - (n_folds - i) * test_size
        block = unique[start:start + test_size]
        folds.append(Fold(i, block[0], block[-1]))
    return folds


def split_fold(rows: pd.DataFrame, fold: Fold) -> tuple[pd.DataFrame, pd.DataFrame]:
    train = rows[(rows["target_ts"] <= fold.test_start) & rows["y"].notna()]
    test = rows[(rows["origin_ts"] >= fold.test_start) & (rows["origin_ts"] <= fold.test_end) & rows["y"].notna()]
    return train, test


def run_backtest(rows: pd.DataFrame, cfg: FreqConfig, n_folds: int = 4, test_size: int | None = None,
                 params: dict | None = None, drop_groups: tuple[str, ...] = ()) -> pd.DataFrame:
    """Return one row per test (station, origin, horizon) with the truth, every baseline and the model."""
    test_size = test_size or (7 * 24 if cfg.name == "hourly" else 30)
    cols = feature_columns(cfg, drop_groups)
    out = []
    for fold in rolling_origin_folds(rows["origin_ts"], n_folds, test_size):
        train, test = split_fold(rows, fold)
        if train.empty or test.empty:
            continue
        model = QuantileModel(cols, params).fit(train)
        q = model.predict(test)
        res = test[["station_id", "origin_ts", "target_ts", "horizon", "hour", "y"]].copy()
        res = res.join(baseline_predictions(test))
        res["lgbm_q10"] = q["q10"]
        res["lgbm"] = q["q50"]
        res["lgbm_q90"] = q["q90"]
        res["fold"] = fold.index
        out.append(res)
    if not out:
        raise ValueError("no fold produced predictions")
    return pd.concat(out, ignore_index=True)
