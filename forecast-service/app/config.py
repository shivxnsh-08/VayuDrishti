"""Settings from environment variables only (never hard-code secrets)."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Settings:
    model_dir: Path
    rules_file: Path
    groq_api_key: str | None
    groq_model: str
    llm_timeout_s: float
    min_history_points: int = 24

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            model_dir=Path(os.getenv("MODEL_DIR", ROOT / "models" / "artifacts")),
            rules_file=Path(os.getenv("ADVICE_RULES_FILE", ROOT / "advice" / "rules.yaml")),
            groq_api_key=os.getenv("GROQ_API_KEY") or None,
            # VERIFY: model id available on your Groq account.
            groq_model=os.getenv("GROQ_MODEL", "llama-3.1-8b-instant"),
            llm_timeout_s=float(os.getenv("LLM_TIMEOUT_S", "5")),
            min_history_points=int(os.getenv("MIN_HISTORY_POINTS", "24")),
        )
