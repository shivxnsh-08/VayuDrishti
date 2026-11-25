"""Output validation for LLM-phrased advice.

A message is rejected (and the fixed template used instead) when it:
* contains any number that was not supplied by the system or present in the chosen templates
  (so "N95" is allowed when WEAR_N95 was chosen, but an invented "AQI 450" is not);
* uses medication, dosing or diagnosis language;
* is empty or too long for a notification.
Digits are normalised first, so Devanagari numerals (e.g. ३२०) are checked the same way.
"""
from __future__ import annotations

import re

_DEVANAGARI = str.maketrans("०१२३४५६७८९", "0123456789")
_NUMBER = re.compile(r"\d+(?:[.,]\d+)?")
MAX_LEN = 600

_BANNED = [re.compile(p, re.IGNORECASE) for p in (
    r"\bdos(e|es|age|ing)\b", r"\bmg\b", r"\bmilligram", r"\bdiagnos", r"\bprescri", r"\btablets?\b",
    r"\bpills?\b", r"\bpuffs?\b", r"\bsteroid", r"\bantibiotic", r"\bcure[sd]?\b", r"\bmedicat",
    r"\binhaler", r"\bnebuli", r"\btreat(ment)?\b",
    r"खुराक", r"निदान", r"गोली", r"दवा", r"इलाज",
)]


def _canonical(token: str) -> str:
    token = token.replace(",", ".")
    try:
        f = float(token)
    except ValueError:
        return token
    return str(int(f)) if f.is_integer() else str(f)


def numbers_in(text: str) -> set[str]:
    return {_canonical(m) for m in _NUMBER.findall(text.translate(_DEVANAGARI))}


def validate_message(message: str, allowed_numbers: set[str]) -> tuple[bool, str | None]:
    if not message or not message.strip():
        return False, "empty"
    if len(message) > MAX_LEN:
        return False, "too_long"
    extra = numbers_in(message) - allowed_numbers
    if extra:
        return False, f"unexpected_numbers:{sorted(extra)}"
    for pattern in _BANNED:
        if pattern.search(message):
            return False, f"medical_language:{pattern.pattern}"
    return True, None
