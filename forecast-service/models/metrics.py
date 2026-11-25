"""Evaluation metrics for backtest output (experiments X2, X3, X5).

All methods are compared on the same rows: rows where any method has no prediction
(e.g. "same hour yesterday" in the first day of data) are dropped for every method.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from models.baselines import BASELINES
from models.categories import categorize, category_names

METHODS = [*BASELINES.keys(), "lgbm"]


def comparable(preds: pd.DataFrame, methods=METHODS) -> pd.DataFrame:
    return preds.dropna(subset=["y", *methods])


def _scores(df: pd.DataFrame, methods) -> dict:
    out = {"n": len(df)}
    truth_cat = categorize(df["y"])
    for m in methods:
        err = df[m] - df["y"]
        out[f"{m}_mae"] = float(np.mean(np.abs(err)))
        out[f"{m}_rmse"] = float(np.sqrt(np.mean(err ** 2)))
        out[f"{m}_cat_acc"] = float(np.mean(categorize(df[m]) == truth_cat))
    return out


def summary(preds: pd.DataFrame, methods=METHODS) -> pd.DataFrame:
    df = comparable(preds, methods)
    return pd.DataFrame([_scores(df, methods)])


def by(preds: pd.DataFrame, key: str, methods=METHODS) -> pd.DataFrame:
    df = comparable(preds, methods)
    rows = [{key: k, **_scores(g, methods)} for k, g in df.groupby(key)]
    return pd.DataFrame(rows)


def best_baseline_per_station(preds: pd.DataFrame, model: str = "lgbm") -> pd.DataFrame:
    """Per station: model MAE vs the best baseline. ``serve`` is what the API should use there."""
    table = by(preds, "station_id", [*BASELINES.keys(), model])
    base_cols = [f"{b}_mae" for b in BASELINES]
    table["best_baseline"] = table[base_cols].idxmin(axis=1).str.replace("_mae", "", regex=False)
    table["best_baseline_mae"] = table[base_cols].min(axis=1)
    table["model_beats_baseline"] = table[f"{model}_mae"] < table["best_baseline_mae"]
    table["serve"] = np.where(table["model_beats_baseline"], "MODEL", table["best_baseline"])
    return table


def confusion(preds: pd.DataFrame, method: str = "lgbm") -> pd.DataFrame:
    df = comparable(preds, [*BASELINES.keys(), method])
    names = category_names()
    return pd.crosstab(pd.Categorical(categorize(df["y"]), categories=names),
                       pd.Categorical(categorize(df[method]), categories=names),
                       rownames=["actual"], colnames=["predicted"], dropna=False)


def interval_coverage(preds: pd.DataFrame, lower: str = "lgbm_q10", upper: str = "lgbm_q90") -> pd.DataFrame:
    """Share of truths inside [lower, upper] per horizon; nominal is 0.8 for q10..q90."""
    df = preds.dropna(subset=["y", lower, upper])
    inside = (df["y"] >= df[lower]) & (df["y"] <= df[upper])
    width = df[upper] - df[lower]
    return (pd.DataFrame({"horizon": df["horizon"], "inside": inside, "width": width})
            .groupby("horizon").agg(coverage=("inside", "mean"), mean_width=("width", "mean"), n=("inside", "size"))
            .reset_index())
