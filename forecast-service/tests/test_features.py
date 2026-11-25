"""Leakage tests: features at origin t must not change when the future changes."""
import dataclasses

import numpy as np
import pandas as pd
import pytest

from features.build import HOURLY, build_rows, horizon_rows, origin_features
from features.cleaning import regularize



def _series(n=24 * 10):
    idx = pd.date_range("2026-01-01 00:30", periods=n, freq="h", tz="UTC")
    return pd.Series(np.arange(n, dtype=float) + 100, index=idx)


def test_origin_features_do_not_use_future_values():
    s = _series()
    t = s.index[150]
    before = origin_features(s, HOURLY).loc[t]
    corrupted = s.copy()
    corrupted.loc[corrupted.index > t] = 9999.0
    after = origin_features(corrupted, HOURLY).loc[t]
    pd.testing.assert_series_equal(before, after)


def test_horizon_rows_features_do_not_use_future_values_only_target_does():
    s = _series()
    t = s.index[200]
    rows = horizon_rows(s, HOURLY)
    corrupted = s.copy()
    corrupted.loc[corrupted.index > t] = 9999.0
    rows_c = horizon_rows(corrupted, HOURLY)
    a = rows[rows.origin_ts == t].drop(columns=["y"]).reset_index(drop=True)
    b = rows_c[rows_c.origin_ts == t].drop(columns=["y"]).reset_index(drop=True)
    pd.testing.assert_frame_equal(a, b)
    # ...while the target did change, proving the test can detect a difference
    assert (rows_c[rows_c.origin_ts == t]["y"] == 9999.0).all()


def test_seasonal_features_point_at_target_hour_yesterday():
    s = _series()
    rows = horizon_rows(s, HOURLY)
    r = rows[(rows.origin_ts == s.index[100]) & (rows.horizon == 5)].iloc[0]
    target = s.index[100] + pd.Timedelta(hours=5)
    assert r["target_ts"] == target
    assert r["seasonal_1"] == s.loc[target - pd.Timedelta(hours=24)]
    assert r["lag_0"] == s.iloc[100]
    assert r["y"] == s.loc[target]
    # calendar features are in local (IST) time: 00:30 UTC + 100h + 5h -> 15:00 IST on day 5
    assert r["hour"] == target.tz_convert("Asia/Kolkata").hour


def test_horizon_longer_than_season_is_rejected():
    bad = dataclasses.replace(HOURLY, horizon=30)
    with pytest.raises(ValueError):
        horizon_rows(_series(), bad)


def test_build_rows_drops_origins_without_current_value(hourly_df):
    reg = regularize(hourly_df, "h", 3)
    reg.loc[10, "aqi"] = np.nan
    rows = build_rows(reg, HOURLY)
    assert rows["lag_0"].notna().all()
    assert set(rows["station_id"]) == {"s1", "s2", "s3"}
    assert rows["horizon"].between(1, 24).all()


def test_origin_stride_is_resolution_independent_and_keeps_the_latest_period():
    """Regression: astype("int64") made the stride depend on pandas' datetime resolution (ns vs us)."""
    idx = pd.date_range("2026-01-01 00:30", periods=24 * 10, freq="h", tz="UTC")
    for unit in ("ns", "us"):
        df = pd.DataFrame({"station_id": "a", "ts": idx.as_unit(unit), "aqi": range(len(idx))})
        rows = build_rows(regularize(df, "h", 3), HOURLY, origin_stride=6)
        origins = pd.Series(sorted(rows["origin_ts"].unique()))
        assert (origins.diff().dropna() == pd.Timedelta(hours=6)).all(), unit
        assert origins.iloc[-1] >= idx[-1] - pd.Timedelta(hours=5), unit
