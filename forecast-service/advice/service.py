"""POST /advice: rules choose action codes, the LLM (optional) rewords, validation guards,
the template is the fallback, and the disclaimer is always appended by code."""
from __future__ import annotations

import logging

from advice.phrasing import Phraser
from advice.rules import RuleBook, action_codes, template_message
from advice.validation import numbers_in, validate_message
from app.schemas import AdviceRequest, AdviceResponse

log = logging.getLogger(__name__)

CATEGORY_LABELS = {
    "en": {"GOOD": "Good", "SATISFACTORY": "Satisfactory", "MODERATE": "Moderate", "POOR": "Poor",
           "VERY_POOR": "Very Poor", "SEVERE": "Severe"},
    "hi": {"GOOD": "अच्छा", "SATISFACTORY": "संतोषजनक", "MODERATE": "मध्यम", "POOR": "खराब",
           "VERY_POOR": "बहुत खराब", "SEVERE": "गंभीर"},
}


def make_advice(req: AdviceRequest, book: RuleBook, phraser: Phraser | None) -> AdviceResponse:
    codes = action_codes(book, req.category, req.health_profile, req.trend)
    sentences = [book.sentence(c, req.language) for c in codes]
    disclaimer = book.disclaimer.get(req.language, book.disclaimer["en"])

    if phraser is not None:
        allowed = numbers_in(" ".join(req.numbers.values()))
        for c in codes:   # numbers that appear in the chosen sentences (e.g. "N95") are allowed
            allowed |= numbers_in(book.sentence(c, "en")) | numbers_in(book.sentence(c, req.language))
        try:
            text = phraser.phrase(CATEGORY_LABELS[req.language][req.category], req.numbers, sentences, req.language)
            ok, reason = validate_message(text, allowed)
            if ok:
                return AdviceResponse(message=f"{text.strip()}\n{disclaimer}", action_codes=codes, source="LLM")
            log.warning("LLM output rejected (%s); using template", reason)
        except Exception as e:  # any LLM failure degrades to the template
            log.warning("LLM phrasing failed (%s); using template", type(e).__name__)

    return AdviceResponse(message=f"{template_message(book, codes, req.language)}\n{disclaimer}",
                          action_codes=codes, source="TEMPLATE")
