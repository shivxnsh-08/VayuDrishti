"""AQI categories from config/aqi_bands.yaml (kept in sync with the backend's aqi.yml)."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

import numpy as np
import yaml

BANDS_FILE = Path(__file__).resolve().parent.parent / "config" / "aqi_bands.yaml"


@lru_cache(maxsize=1)
def bands() -> list[dict]:
    with open(BANDS_FILE, encoding="utf-8") as f:
        return sorted(yaml.safe_load(f)["bands"], key=lambda b: b["min"])


def category_names() -> list[str]:
    return [b["category"] for b in bands()]


def categorize(values) -> np.ndarray:
    """Vectorised AQI -> category name. NaN stays None. Above the top band = top category."""
    v = np.asarray(values, dtype=float)
    b = bands()
    # Upper edges between bands: a value belongs to the first band whose max >= value.
    edges = np.array([band["max"] for band in b[:-1]], dtype=float)
    idx = np.searchsorted(edges, np.round(v), side="left")
    names = np.array([band["category"] for band in b], dtype=object)
    out = names[np.clip(idx, 0, len(b) - 1)]
    out[np.isnan(v)] = None
    return out
