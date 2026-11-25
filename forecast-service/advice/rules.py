"""Rules table: decides WHAT to recommend. Pure and deterministic."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class Rule:
    categories: frozenset[str]
    profiles: frozenset[str] | None
    trends: frozenset[str] | None
    actions: tuple[str, ...]


@dataclass(frozen=True)
class RuleBook:
    rules: tuple[Rule, ...]
    priority: tuple[str, ...]
    templates: dict[str, dict[str, str]]
    disclaimer: dict[str, str]

    def sentence(self, code: str, language: str) -> str:
        t = self.templates[code]
        return t.get(language) or t["en"]


def load_rules(path: Path) -> RuleBook:
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    sensitive = set(raw.get("sensitive_profiles", []))

    def expand(profiles):
        if profiles is None:
            return None
        out = set()
        for p in profiles:
            out |= sensitive if p == "SENSITIVE" else {p}
        return frozenset(out)

    rules = tuple(Rule(frozenset(r["categories"]), expand(r.get("profiles")),
                       frozenset(r["trends"]) if r.get("trends") else None, tuple(r["actions"]))
                  for r in raw["rules"])
    book = RuleBook(rules, tuple(raw["priority"]), raw["templates"], raw["disclaimer"])
    missing = {a for r in rules for a in r.actions} - set(book.templates)
    if missing:
        raise ValueError(f"actions without templates: {sorted(missing)}")
    return book


def action_codes(book: RuleBook, category: str, profile: str, trend: str) -> list[str]:
    """Union of actions from every matching rule, ordered by priority. Never empty."""
    codes: set[str] = set()
    for r in book.rules:
        if category not in r.categories:
            continue
        if r.profiles is not None and profile not in r.profiles:
            continue
        if r.trends is not None and trend not in r.trends:
            continue
        codes.update(r.actions)
    if not codes:
        codes = {"CHECK_AGAIN_LATER"}
    rank = {c: i for i, c in enumerate(book.priority)}
    return sorted(codes, key=lambda c: rank.get(c, len(rank)))


def template_message(book: RuleBook, codes: list[str], language: str) -> str:
    return " ".join(book.sentence(c, language) for c in codes)
