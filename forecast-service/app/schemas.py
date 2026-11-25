"""Wire schemas. Must match backend ForecastDtos / AdviceDtos; /contracts holds shared examples
that both test suites validate."""
from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

Category = Literal["GOOD", "SATISFACTORY", "MODERATE", "POOR", "VERY_POOR", "SEVERE"]
Trend = Literal["IMPROVING", "STABLE", "WORSENING"]
HealthProfile = Literal["GENERAL", "ASTHMA", "CHILD", "ELDERLY", "EXERCISER"]
Language = Literal["en", "hi"]


class ReadingPoint(BaseModel):
    ts: datetime
    aqi: int = Field(ge=0, le=1000)


class ForecastRequest(BaseModel):
    station_id: str = Field(min_length=1, max_length=64)
    readings: list[ReadingPoint] = Field(min_length=1, max_length=24 * 90)
    horizon_hours: int = Field(default=24, ge=1, le=24)


class Prediction(BaseModel):
    target_ts: datetime
    predicted_aqi: int
    lower: int | None = None
    upper: int | None = None


class ForecastResponse(BaseModel):
    station_id: str
    generated_at: datetime
    origin_ts: datetime
    model_version: str
    method: str
    predictions: list[Prediction]


class AdviceRequest(BaseModel):
    category: Category
    trend: Trend = "STABLE"
    health_profile: HealthProfile
    language: Language = "en"
    numbers: dict[str, str] = Field(default_factory=dict, max_length=10)


class AdviceResponse(BaseModel):
    message: str
    action_codes: list[str]
    source: Literal["LLM", "TEMPLATE"]
