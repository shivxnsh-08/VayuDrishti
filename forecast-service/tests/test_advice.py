from pathlib import Path

import pytest

from advice.rules import action_codes, load_rules
from advice.service import make_advice
from advice.validation import numbers_in, validate_message
from app.schemas import AdviceRequest

RULES = load_rules(Path(__file__).resolve().parent.parent / "advice" / "rules.yaml")


def test_rules_are_conservative_for_sensitive_profiles():
    asthma = action_codes(RULES, "POOR", "ASTHMA", "STABLE")
    general = action_codes(RULES, "POOR", "GENERAL", "STABLE")
    assert "AVOID_OUTDOOR_EXERCISE" in asthma and "FOLLOW_CARE_PLAN" in asthma
    assert "AVOID_OUTDOOR_EXERCISE" not in general
    assert action_codes(RULES, "GOOD", "CHILD", "STABLE") == ["NO_SPECIAL_PRECAUTIONS"]


def test_rules_order_and_trend():
    codes = action_codes(RULES, "SEVERE", "GENERAL", "WORSENING")
    assert codes[0] == "STAY_INDOORS"
    assert "PLAN_AROUND_WORST_HOURS" in codes
    assert len(codes) == len(set(codes))


def test_number_extraction_handles_devanagari_and_leading_zeros():
    assert numbers_in("AQI ३२० at 07:00") == {"320", "7", "0"}


@pytest.mark.parametrize("msg,ok", [
    ("Air may reach 320 around 7. Avoid outdoor exercise.", True),
    ("Wear an N95 mask.", True),
    ("Air may reach 450 tonight.", False),                 # invented number
    ("AQI ४५० तक जा सकता है।", False),                       # invented number in Devanagari digits
    ("Take two puffs of your inhaler.", False),             # dosing/medication
    ("This may cause a diagnosis of asthma.", False),
    ("", False),
])
def test_validation(msg, ok):
    allowed = {"320", "7", "0", "95"}
    assert validate_message(msg, allowed)[0] is ok


class StubPhraser:
    def __init__(self, reply=None, error=None):
        self.reply, self.error = reply, error

    def phrase(self, category_label, numbers, sentences, language):
        if self.error:
            raise self.error
        return self.reply


def _req(**kw):
    base = dict(category="VERY_POOR", trend="WORSENING", health_profile="ASTHMA", language="en",
                numbers={"aqi": "320", "time": "07:00"})
    return AdviceRequest(**{**base, **kw})


def test_valid_llm_output_is_used_with_disclaimer():
    r = make_advice(_req(), RULES, StubPhraser("AQI may hit 320 around 7. Stay in and wear an N95 if you go out."))
    assert r.source == "LLM"
    assert r.message.endswith("General guidance only, not medical advice.")


def test_invalid_llm_output_falls_back_to_template():
    r = make_advice(_req(), RULES, StubPhraser("AQI may hit 480! Take 2 tablets."))
    assert r.source == "TEMPLATE"
    assert "480" not in r.message


def test_llm_failure_and_no_llm_use_template_in_requested_language():
    r = make_advice(_req(language="hi"), RULES, StubPhraser(error=TimeoutError()))
    assert r.source == "TEMPLATE"
    assert "चिकित्सा सलाह नहीं" in r.message
    r2 = make_advice(_req(), RULES, None)
    assert r2.source == "TEMPLATE" and "N95" in r2.message
