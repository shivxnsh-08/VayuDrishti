"""LLM phrasing (Groq). The model receives only: category name, system numbers, the chosen
action sentences and the target language, and returns a short message. It is never asked
what to recommend."""
from __future__ import annotations

import json
from typing import Protocol

LANG_NAMES = {"en": "simple English", "hi": "simple Hindi (Devanagari script)"}

SYSTEM_PROMPT = (
    "You rewrite air-quality guidance for a phone notification. "
    "Write at most 3 short, friendly sentences in {language}. "
    "Use ONLY the facts and numbers given. Do not add any other numbers, times or facts. "
    "Keep every recommendation; do not add new ones. "
    "Never mention medicines, doses, diagnoses or treatments. "
    "Do not add a disclaimer; it is added separately. Output only the message text."
)


class Phraser(Protocol):
    def phrase(self, category_label: str, numbers: dict[str, str], sentences: list[str], language: str) -> str:
        ...


def build_messages(category_label: str, numbers: dict[str, str], sentences: list[str], language: str) -> list[dict]:
    payload = {"air_quality_category": category_label, "numbers": numbers, "recommendations": sentences}
    return [
        {"role": "system", "content": SYSTEM_PROMPT.format(language=LANG_NAMES.get(language, "simple English"))},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
    ]


class GroqPhraser:
    """VERIFY: Groq SDK interface and model id. Reads the key from GROQ_API_KEY via Settings."""

    def __init__(self, api_key: str, model: str, timeout_s: float):
        from groq import Groq  # imported lazily so tests and keyless runs do not need it configured

        self.client = Groq(api_key=api_key, timeout=timeout_s, max_retries=1)
        self.model = model

    def phrase(self, category_label: str, numbers: dict[str, str], sentences: list[str], language: str) -> str:
        resp = self.client.chat.completions.create(
            model=self.model,
            messages=build_messages(category_label, numbers, sentences, language),
            temperature=0.2,
            max_tokens=220,
        )
        return (resp.choices[0].message.content or "").strip()
