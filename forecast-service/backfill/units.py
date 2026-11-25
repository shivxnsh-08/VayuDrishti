"""Map OpenAQ parameters/units to CPCB pollutant codes and units.

CPCB breakpoints use ug/m3 for every pollutant except CO (mg/m3). Mass units are converted
exactly. Volume mixing ratios (ppm, ppb) are NOT converted: that needs temperature and pressure
assumptions, so such values are skipped and counted instead of guessed.
"""
from __future__ import annotations

# VERIFY parameter names against GET /v3/parameters.
PARAMETERS = {"pm25": "PM25", "pm10": "PM10", "no2": "NO2", "so2": "SO2", "o3": "O3", "co": "CO", "nh3": "NH3"}

_UG = {"µg/m³", "ug/m3", "μg/m³", "µg/m3", "μg/m3"}
_MG = {"mg/m³", "mg/m3"}


def normalize(parameter: str, unit: str, value: float) -> tuple[str | None, float | None, str | None]:
    """Return (pollutant, value_in_cpcb_unit, skip_reason)."""
    pollutant = PARAMETERS.get((parameter or "").lower())
    if pollutant is None:
        return None, None, "unsupported_parameter"
    if value is None or value != value or value < 0:
        return pollutant, None, "invalid_value"
    u = (unit or "").strip()
    if u in _UG:
        return pollutant, (value / 1000.0 if pollutant == "CO" else value), None
    if u in _MG:
        return pollutant, (value if pollutant == "CO" else value * 1000.0), None
    return pollutant, None, f"unconvertible_unit:{u or 'none'}"
