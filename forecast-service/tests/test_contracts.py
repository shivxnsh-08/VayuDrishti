"""Contract test: the shared examples in /contracts must round-trip through the Pydantic schemas.
The Java side (ContractTest.java) validates the same files."""
import json
from pathlib import Path

import pytest

from app.schemas import AdviceRequest, AdviceResponse, ForecastRequest, ForecastResponse

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"


def _paths(obj, prefix=""):
    out = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.add(prefix + k)
            out |= _paths(v, prefix + k + ".")
    elif isinstance(obj, list) and obj:
        out |= _paths(obj[0], prefix + "[].")
    return out


@pytest.mark.parametrize("file,model", [
    ("forecast_request.example.json", ForecastRequest),
    ("forecast_response.example.json", ForecastResponse),
    ("advice_request.example.json", AdviceRequest),
    ("advice_response.example.json", AdviceResponse),
])
def test_examples_round_trip(file, model):
    example = json.loads((CONTRACTS / file).read_text(encoding="utf-8"))
    parsed = model.model_validate(example)
    dumped = json.loads(parsed.model_dump_json())
    # dict-valued free-form fields (numbers) are compared by their example keys too
    assert _paths(dumped) == _paths(example)
