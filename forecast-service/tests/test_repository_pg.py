"""PostgresRepository against a real PostgreSQL/PostGIS (skipped unless PGTEST_DSN is set).

    docker run -d --rm --name vd-pgtest -e POSTGRES_PASSWORD=test -p 127.0.0.1:55432:5432 postgis/postgis:16-3.4
    PGTEST_DSN="host=127.0.0.1 port=55432 dbname=postgres user=postgres password=test" pytest tests/test_repository_pg.py

The schema is created from the backend's Flyway files (V1 and V4), so the SQL here is checked against the
real tables. Never point PGTEST_DSN at the project database: the test drops and recreates tables.
"""
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

DSN = os.getenv("PGTEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="PGTEST_DSN not set")
MIGRATIONS = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" / "resources" / "db" / "migration"
NOW = datetime(2026, 12, 20, 12, 30, tzinfo=timezone.utc)


@pytest.fixture
def repo():
    import psycopg
    from lifecycle.repository import PostgresRepository
    with psycopg.connect(DSN, autocommit=True) as c:
        c.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
        c.execute((MIGRATIONS / "V1__init_schema.sql").read_text(encoding="utf-8"))
        c.execute((MIGRATIONS / "V4__ml_lifecycle.sql").read_text(encoding="utf-8"))
        c.execute("""INSERT INTO stations (id, name, geom) VALUES ('ito', 'ITO',
                     ST_SetSRID(ST_MakePoint(77.24, 28.63), 4326)::geography)""")
        for h in range(5):
            c.execute("INSERT INTO station_aqi (station_id, ts, aqi) VALUES ('ito', %s, %s)", (NOW - timedelta(hours=h), 100 + h))
        c.execute("INSERT INTO station_aqi (station_id, ts, aqi) VALUES ('ito', %s, NULL)", (NOW - timedelta(hours=9),))
        c.execute("""INSERT INTO readings (station_id, pollutant, ts, value, quality) VALUES
                     ('ito', 'PM25', %s, 100, 'OK'), ('ito', 'NH3', %s, NULL, 'MISSING')""", (NOW, NOW))
    yield PostgresRepository(DSN)


def test_history_excludes_null_aqi_and_respects_since(repo):
    h = repo.load_history(NOW - timedelta(hours=3))
    assert len(h) == 4 and h["ts"].dt.tz is not None and h["aqi"].tolist() == [103.0, 102.0, 101.0, 100.0]   # ascending time; older hours had higher values
    assert len(repo.load_history(NOW - timedelta(days=1))) == 5          # the NULL-aqi row is excluded
    q = repo.quality_summary(NOW - timedelta(days=1))
    assert q["readings"] == 2 and q["missing_share"] == 0.5


def test_runs_state_and_events_round_trip(repo):
    run_id = repo.insert_run("INITIAL", 1)
    repo.finish_run(run_id, status="NOT_PROMOTED", metrics={"model_mae": 12.5}, reasons="does not beat persistence",
                    dataset_fingerprint="abc", data_last_ts=NOW)
    r = repo.runs()[0]
    assert r["status"] == "NOT_PROMOTED" and r["metrics"]["model_mae"] == 12.5 and r["finished_at"] is not None
    repo.set_state("readiness", {"ready": False})
    repo.set_state("readiness", {"ready": True})
    assert repo.get_state("readiness") == {"ready": True}
    repo.add_event("READINESS", "met", {"span_hours": 700})
    assert repo.events()[0]["detail"] == {"span_hours": 700}


def test_advisory_lock_is_exclusive_and_released_when_the_holder_dies(repo):
    from lifecycle.repository import PostgresRepository
    other = PostgresRepository(DSN)
    assert repo.try_lock()
    assert not other.try_lock() and not other.lock_is_free()
    run_id = repo.insert_run("INITIAL", 1)
    assert other.mark_interrupted() == 0                   # holder alive: running row untouched
    repo.unlock()

    # A separate process takes the lock and is killed (as in a container restart).
    holder = subprocess.Popen([sys.executable, "-c", (
        "import psycopg,sys,time; c=psycopg.connect(sys.argv[1], autocommit=True); "
        "c.execute('SELECT pg_advisory_lock(%s)', (0x56415955,)); print('locked', flush=True); time.sleep(60)"), DSN],
        stdout=subprocess.PIPE, text=True)
    assert holder.stdout.readline().strip() == "locked"
    assert not other.lock_is_free()
    holder.kill()
    holder.wait()
    for _ in range(50):                                     # Postgres notices the dropped connection
        if other.lock_is_free():
            break
        import time
        time.sleep(0.1)
    assert other.lock_is_free()
    assert other.mark_interrupted() == 1
    assert other.runs()[0]["id"] == run_id and other.runs()[0]["status"] == "INTERRUPTED"
