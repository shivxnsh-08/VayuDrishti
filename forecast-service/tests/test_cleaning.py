import numpy as np
import pandas as pd

from features.cleaning import fill_short_gaps, mark_impossible, regularize


def test_impossible_values_become_missing():
    s = pd.Series([10, -5, 600, 250, "bad"])
    out = mark_impossible(s)
    assert out.tolist()[0] == 10 and out.tolist()[3] == 250
    assert out.isna().tolist() == [False, True, True, False, True]


def test_only_short_gaps_are_filled():
    s = pd.Series([1.0, np.nan, np.nan, 4.0, np.nan, np.nan, np.nan, np.nan, 9.0])
    out = fill_short_gaps(s, max_gap=3)
    assert out.tolist()[:4] == [1.0, 1.0, 1.0, 4.0]          # gap of 2 filled
    assert out.iloc[4:8].isna().all()                          # gap of 4 left entirely missing
    assert out.iloc[8] == 9.0


def test_regularize_keeps_half_hour_grid_and_flags_gaps():
    ts = pd.date_range("2026-01-01 00:30", periods=10, freq="h", tz="UTC")
    df = pd.DataFrame({"station_id": "a", "ts": ts, "aqi": range(100, 110)})
    df = df.drop(index=[3, 5, 6, 7, 8])                        # one short gap, one long gap
    reg = regularize(df, "h", max_gap=3)
    assert len(reg) == 10
    assert (reg["ts"].dt.minute == 30).all()
    assert reg.loc[3, "aqi"] == 102 and reg.loc[3, "imputed"]
    # gap 5..8 is 4 steps long (> 3): stays missing
    assert reg.loc[5:8, "gap"].all()


def test_duplicates_keep_last_and_empty_input():
    ts = pd.Timestamp("2026-01-01 00:30", tz="UTC")
    df = pd.DataFrame({"station_id": ["a", "a"], "ts": [ts, ts], "aqi": [100, 120]})
    assert regularize(df, "h", 3)["aqi"].tolist() == [120]
    assert regularize(df.iloc[0:0], "h", 3).empty
