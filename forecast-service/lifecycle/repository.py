"""Persistence for the lifecycle: history reads, training-run records, events, state and the job lock.

``PostgresRepository`` is the real one. ``InMemoryRepository`` has identical behaviour for unit tests.

Concurrency: a Postgres **session advisory lock** guards training. It is held by the training job's own
connection for the whole run; if the process dies (crash, container restart), Postgres releases it, so a
``RUNNING`` row whose lock is free is provably orphaned and is marked ``INTERRUPTED``.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Protocol

import pandas as pd

LOCK_KEY = 0x56415955   # "VAYU": arbitrary constant shared by all lifecycle processes
COMPLETED = ("PROMOTED", "NOT_PROMOTED", "INSUFFICIENT_DATA")
FAILED_LIKE = ("FAILED", "INTERRUPTED")


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Repository(Protocol):
    def load_history(self, since: datetime) -> pd.DataFrame: ...
    def quality_summary(self, since: datetime) -> dict: ...
    def try_lock(self) -> bool: ...
    def unlock(self) -> None: ...
    def lock_is_free(self) -> bool: ...
    def insert_run(self, trigger: str, attempt: int) -> int: ...
    def finish_run(self, run_id: int, **fields) -> None: ...
    def runs(self, limit: int = 50) -> list[dict]: ...
    def mark_interrupted(self) -> int: ...
    def get_state(self, key: str) -> dict | None: ...
    def set_state(self, key: str, value: dict) -> None: ...
    def add_event(self, event_type: str, message: str, detail: dict | None = None) -> None: ...
    def events(self, limit: int = 100) -> list[dict]: ...


_RUN_COLUMNS = ["id", "trigger", "status", "attempt", "started_at", "finished_at", "dataset_fingerprint",
                "data_first_ts", "data_last_ts", "observations", "stations", "model_version", "model_name",
                "metrics", "reasons", "error"]


class PostgresRepository:
    def __init__(self, conninfo: str = ""):
        import psycopg   # imported lazily: unit tests and keyless runs do not need it
        self._psycopg = psycopg
        self._conninfo = conninfo   # "" = use PG* environment variables
        self._lock_conn = None

    def _connect(self):
        return self._psycopg.connect(self._conninfo, autocommit=True, connect_timeout=5)

    # ---- data -------------------------------------------------------------------------------
    def load_history(self, since: datetime) -> pd.DataFrame:
        with self._connect() as c, c.cursor() as cur:
            cur.execute("""SELECT station_id, ts, aqi FROM station_aqi
                           WHERE aqi IS NOT NULL AND ts >= %s ORDER BY station_id, ts""", (since,))
            rows = cur.fetchall()
        df = pd.DataFrame(rows, columns=["station_id", "ts", "aqi"])
        df["ts"] = pd.to_datetime(df["ts"], utc=True)
        df["aqi"] = df["aqi"].astype(float)
        return df

    def quality_summary(self, since: datetime) -> dict:
        with self._connect() as c, c.cursor() as cur:
            cur.execute("SELECT quality, count(*) FROM readings WHERE ts >= %s GROUP BY quality", (since,))
            counts = {q: n for q, n in cur.fetchall()}
        total = sum(counts.values())
        return {"readings": total, **{f"{q.lower()}_share": round(n / total, 4) for q, n in counts.items()}} if total else {"readings": 0}

    # ---- lock ---------------------------------------------------------------------------------
    def try_lock(self) -> bool:
        if self._lock_conn is None:
            self._lock_conn = self._connect()
        got = self._lock_conn.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_KEY,)).fetchone()[0]
        if not got:
            self._lock_conn.close()
            self._lock_conn = None
        return bool(got)

    def unlock(self) -> None:
        if self._lock_conn is not None:
            try:
                self._lock_conn.execute("SELECT pg_advisory_unlock(%s)", (LOCK_KEY,))
            finally:
                self._lock_conn.close()
                self._lock_conn = None

    def lock_is_free(self) -> bool:
        with self._connect() as c:
            if c.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_KEY,)).fetchone()[0]:
                c.execute("SELECT pg_advisory_unlock(%s)", (LOCK_KEY,))
                return True
            return False

    # ---- runs ---------------------------------------------------------------------------------
    def insert_run(self, trigger: str, attempt: int) -> int:
        with self._connect() as c:
            return c.execute("""INSERT INTO ml_training_runs (trigger, status, attempt) VALUES (%s, 'RUNNING', %s)
                                RETURNING id""", (trigger, attempt)).fetchone()[0]

    def finish_run(self, run_id: int, **fields) -> None:
        fields.setdefault("finished_at", utcnow())
        if "metrics" in fields and fields["metrics"] is not None:
            fields["metrics"] = json.dumps(fields["metrics"], default=str)
        cols = ", ".join(f"{k} = %s" for k in fields)
        with self._connect() as c:
            c.execute(f"UPDATE ml_training_runs SET {cols} WHERE id = %s", (*fields.values(), run_id))

    def runs(self, limit: int = 50) -> list[dict]:
        with self._connect() as c, c.cursor() as cur:
            cur.execute(f"SELECT {', '.join(_RUN_COLUMNS)} FROM ml_training_runs ORDER BY id DESC LIMIT %s", (limit,))
            return [dict(zip(_RUN_COLUMNS, r)) for r in cur.fetchall()]

    def mark_interrupted(self) -> int:
        if not self.lock_is_free():
            return 0   # a job is genuinely running
        with self._connect() as c:
            return c.execute("""UPDATE ml_training_runs SET status = 'INTERRUPTED', finished_at = now(),
                                error = 'process stopped during training (restart or crash); lock was released'
                                WHERE status = 'RUNNING'""").rowcount

    # ---- state and events ---------------------------------------------------------------------
    def get_state(self, key: str) -> dict | None:
        with self._connect() as c:
            row = c.execute("SELECT value FROM ml_lifecycle_state WHERE key = %s", (key,)).fetchone()
        return row[0] if row else None

    def set_state(self, key: str, value: dict) -> None:
        with self._connect() as c:
            c.execute("""INSERT INTO ml_lifecycle_state (key, value, updated_at) VALUES (%s, %s, now())
                         ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                      (key, json.dumps(value, default=str)))

    def add_event(self, event_type: str, message: str, detail: dict | None = None) -> None:
        with self._connect() as c:
            c.execute("INSERT INTO ml_lifecycle_events (event_type, message, detail) VALUES (%s, %s, %s)",
                      (event_type, message, json.dumps(detail, default=str) if detail else None))

    def events(self, limit: int = 100) -> list[dict]:
        with self._connect() as c, c.cursor() as cur:
            cur.execute("SELECT id, at, event_type, message, detail FROM ml_lifecycle_events ORDER BY id DESC LIMIT %s",
                        (limit,))
            return [dict(zip(["id", "at", "event_type", "message", "detail"], r)) for r in cur.fetchall()]


class InMemoryRepository:
    """Same contract as PostgresRepository, for tests. ``history`` is the station_aqi table."""

    def __init__(self, history: pd.DataFrame | None = None):
        self.history = history if history is not None else pd.DataFrame(columns=["station_id", "ts", "aqi"])
        self._runs: list[dict] = []
        self._events: list[dict] = []
        self._state: dict[str, dict] = {}
        self.lock_held = False        # held by "some process"
        self._mine = False

    def load_history(self, since: datetime) -> pd.DataFrame:
        h = self.history.copy()
        h["ts"] = pd.to_datetime(h["ts"], utc=True)
        return h[h["ts"] >= pd.Timestamp(since)].sort_values(["station_id", "ts"]).reset_index(drop=True)

    def quality_summary(self, since: datetime) -> dict:
        return {"readings": 0}

    def try_lock(self) -> bool:
        if self.lock_held:
            return False
        self.lock_held = self._mine = True
        return True

    def unlock(self) -> None:
        if self._mine:
            self.lock_held = self._mine = False

    def lock_is_free(self) -> bool:
        return not self.lock_held

    def insert_run(self, trigger: str, attempt: int) -> int:
        run = {c: None for c in _RUN_COLUMNS}
        run.update(id=len(self._runs) + 1, trigger=trigger, status="RUNNING", attempt=attempt, started_at=utcnow())
        self._runs.append(run)
        return run["id"]

    def finish_run(self, run_id: int, **fields) -> None:
        fields.setdefault("finished_at", utcnow())
        self._runs[run_id - 1].update(fields)

    def runs(self, limit: int = 50) -> list[dict]:
        return [dict(r) for r in reversed(self._runs)][:limit]

    def mark_interrupted(self) -> int:
        if not self.lock_is_free():
            return 0
        n = 0
        for r in self._runs:
            if r["status"] == "RUNNING":
                r.update(status="INTERRUPTED", finished_at=utcnow(), error="process stopped during training")
                n += 1
        return n

    def get_state(self, key: str) -> dict | None:
        v = self._state.get(key)
        return json.loads(json.dumps(v, default=str)) if v is not None else None

    def set_state(self, key: str, value: dict) -> None:
        self._state[key] = json.loads(json.dumps(value, default=str))

    def add_event(self, event_type: str, message: str, detail: dict | None = None) -> None:
        self._events.append({"id": len(self._events) + 1, "at": utcnow(), "event_type": event_type,
                             "message": message, "detail": detail})

    def events(self, limit: int = 100) -> list[dict]:
        return list(reversed(self._events))[:limit]
