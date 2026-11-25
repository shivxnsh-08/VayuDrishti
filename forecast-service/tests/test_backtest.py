import numpy as np
import pandas as pd
import pytest

from features.build import HOURLY, build_rows
from features.cleaning import regularize
from models import metrics
from models.backtest import rolling_origin_folds, run_backtest, split_fold


@pytest.fixture
def rows(hourly_df):
    return build_rows(regularize(hourly_df, "h", 3), HOURLY)


def test_folds_are_contiguous_and_ordered(rows):
    folds = rolling_origin_folds(rows["origin_ts"], n_folds=3, test_size=48)
    assert len(folds) == 3
    for a, b in zip(folds, folds[1:]):
        assert a.test_end < b.test_start


def test_training_targets_never_reach_into_test_period(rows):
    for fold in rolling_origin_folds(rows["origin_ts"], n_folds=3, test_size=48):
        train, test = split_fold(rows, fold)
        assert train["target_ts"].max() <= test["origin_ts"].min()
        assert train["origin_ts"].max() < test["origin_ts"].min()


def test_too_little_history_is_an_error(rows):
    with pytest.raises(ValueError):
        rolling_origin_folds(rows["origin_ts"].head(10), n_folds=3, test_size=48)


def test_backtest_produces_comparable_predictions(rows):
    preds = run_backtest(rows, HOURLY, n_folds=2, test_size=48, params={"n_estimators": 60})
    for col in ["y", "persistence", "same_hour_yesterday", "same_hour_7d_mean", "lgbm", "lgbm_q10", "lgbm_q90"]:
        assert col in preds
    assert (preds["lgbm_q10"] <= preds["lgbm"]).all() and (preds["lgbm"] <= preds["lgbm_q90"]).all()
    s = metrics.summary(preds).iloc[0]
    assert np.isfinite(s["lgbm_mae"])
    # Synthetic data has a strong daily cycle, which persistence cannot follow at long horizons.
    assert s["lgbm_mae"] < s["persistence_mae"]


def test_metric_helpers(rows):
    preds = run_backtest(rows, HOURLY, n_folds=1, test_size=48, params={"n_estimators": 40})
    cov = metrics.interval_coverage(preds)
    assert cov["coverage"].between(0, 1).all()
    assert set(metrics.by(preds, "horizon")["horizon"]) == set(range(1, 25))
    table = metrics.best_baseline_per_station(preds)
    assert set(table["serve"]) <= {"MODEL", "persistence", "same_hour_yesterday", "same_hour_7d_mean"}
    cm = metrics.confusion(preds)
    assert cm.values.sum() == len(metrics.comparable(preds))


def test_categorize_band_edges():
    from models.categories import categorize
    out = categorize([0, 50, 51, 100, 101, 200, 201, 300, 301, 400, 401, 500, 650, np.nan])
    assert list(out[:-1]) == ["GOOD", "GOOD", "SATISFACTORY", "SATISFACTORY", "MODERATE", "MODERATE", "POOR",
                              "POOR", "VERY_POOR", "VERY_POOR", "SEVERE", "SEVERE", "SEVERE"]
    assert out[-1] is None
