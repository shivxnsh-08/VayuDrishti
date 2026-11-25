# Ingestion reliability

How VayuDrishti keeps AQI ingestion running without manual intervention, and how to tell whether it is
actually delivering fresh data.

## Incident behind this work (2026-11-22 to 11-24)

Every gap in stored data matched a period when the Docker stack was not running (host or Docker
shutdown). All containers had restart policy `no` and Docker Desktop does not start at sign-in, so
nothing restarted ingestion. After a restart the first cron run could be up to 15 minutes away. While the
stack was up, every run succeeded apart from three transient TLS (PKIX) failures on 2026-11-22.
The data.gov.in feed only publishes the latest snapshot, so **hours missed while the stack was off cannot
be recovered from it**.

## Schedule

* Cron `0 5/15 * * * *`, evaluated in **UTC** (`:05, :20, :35, :50`). CPCB publishes a new hour around `:02`,
  so normally one run per hour stores new observations and the other three correctly store **0 new
  observations**. That is not a failure.
* **No overlap:** one run per JVM at a time (`synchronized`). Across instances, a PostgreSQL session advisory
  lock means a second backend skips the run (metric `saanscheck_ingestion_skipped_locked_total`) instead of
  duplicating it.
* **Startup catch-up:** about 20 s after start-up (`startup-delay`), a `RECOVERY` run starts if the last
  successful run is older than 10 minutes (`startup-catch-up-after`).
* **Interrupted runs:** a run left `RUNNING` by a killed process is marked `INTERRUPTED` by the next run.
* An exception, even one that prevents the run from being recorded (for example, the database is down),
  never stops the schedule. It is logged at ERROR and counted in
  `saanscheck_ingestion_unrecorded_failures_total`.

## Retries

| Category | Retried | Counts as source outage |
|---|---|---|
| NETWORK, TIMEOUT, DNS, TLS, SOURCE_5XX | yes | yes |
| RATE_LIMITED (429) | yes, honours `Retry-After` (seconds or HTTP date, max 60 s) | yes |
| PARSE (unexpected payload) | no | yes |
| AUTH (401/403), CLIENT_4XX, DATABASE, CONFIG, UNKNOWN | no | no |

* **Attempts:** up to 4 per page.
* **Backoff:** exponential, 2 s → 4 s → 8 s, capped at 30 s
  (`saanscheck.ingestion.retry.*`).
* **When retries run out:** the run is recorded as `FAILED`, with the category, HTTP status, retry count and
  a redacted error. The API key is never logged.
* **Next try:** the next cron slot. No endless retries.
* **Transport-level resend:** the JDK `HttpURLConnection` may silently resend an idempotent GET once after a
  connection reset. So one attempt can mean up to 2 requests on the wire. The total is still bounded.

## What a run records (`ingestion_runs`)

| Fields | Meaning |
|---|---|
| `trigger` | `SCHEDULED`, `RECOVERY` or `MANUAL` |
| `status` | `SUCCESS`, `PARTIAL` (incomplete snapshot), `FAILED`, `INTERRUPTED` or `SKIPPED` |
| `duration_ms`, `retry_count`, `error_category`, `http_status` | timing and failure detail |
| `fetched` | records received from the source |
| `inserted` | new observations stored |
| `duplicates` | already stored |
| `rejected`, `rejection_reasons` | rejected records, with a count per reason (JSON) |
| `missing` | missing values |
| `stuck` | stuck-sensor flags |
| `latest_source_ts` | newest observation time reported by the source |

Source timestamps are stored exactly as published. Data is never re-timestamped, synthesised or
back-filled. A record dated more than 1 h in the future is rejected (`FUTURE_TIMESTAMP`), so it can never make the
data look fresh.

**Stuck sensor:** a value is flagged only when it is ≥ 50 (`stuck-min-value`) and identical for 6
*consecutive* hours (`stuck-hours`). A gap in the data breaks the sequence. Low values, such as
24-hour-average sub-indices of SO₂, NH₃ and NO₂, are legitimately constant for long periods and are never
flagged.

## Health (`GET /api/v1/ingestion/health`, public)

The first matching rule wins:

| Status | When |
|---|---|
| `UNKNOWN` | no run recorded yet |
| `SOURCE_UNAVAILABLE` | ≥ 2 consecutive failures caused by the source (network, TLS, DNS, 5xx, 429, parse) |
| `FAILED` | ≥ 3 consecutive failures |
| `STALE` | newest observation older than 3 h. A recent `SUCCESS` with 0 new observations means *the source is still returning old observations* |
| `DEGRADED` | any of: newest observation older than 135 min (a healthy feed peaks at about 125 min just before the next hour is published); the last run was `PARTIAL` or failed; no run for 35 min; fewer than half of active stations fresh |
| `HEALTHY` | none of the above |

**Prometheus metrics:**
* `saanscheck_ingestion_health{status}`
* `saanscheck_ingestion_latest_observation_age_minutes`
* `saanscheck_ingestion_minutes_since_last_run`
* `saanscheck_ingestion_minutes_since_last_success`
* `saanscheck_ingestion_consecutive_failures`
* `saanscheck_stations_fresh`
* `saanscheck_ingestion_new_observations_total`
* `saanscheck_ingestion_retries_total`
* `saanscheck_ingestion_errors_total{category}`

**Alerts:**
* A problem status that persists for 10 minutes (`notify-after`) sends **one** Telegram message to every
  admin with a linked Telegram chat.
* A return to `HEALTHY` or `DEGRADED` sends one recovery message.
* Every notification is recorded in `ingestion_health_notifications`. If no admin has linked Telegram, it
  is still recorded with `recipients = 0`, and a warning is logged.

The dashboard's **Data pipeline** card shows the same data and refreshes every 5 minutes.

## Downtime and backfill

The data.gov.in resource holds only the current snapshot, so the platform cannot recover observations
for hours when it was not running. After downtime the dashboard reports `STALE` until a newly published
hour is ingested. Historical data from OpenAQ (`forecast-service/backfill`) is a separate, manual,
clearly labelled import. It is not run automatically and does not fill these gaps.

## Keeping it running

* All compose services use `restart: unless-stopped`, so they come back after a crash or a Docker restart.
  They stay down after an explicit `docker compose stop`.
* **Graceful stop:** on `docker compose stop` or restart, the backend finishes in-flight HTTP requests and lets a
  running ingestion complete (up to 30 s; the container's `stop_grace_period` is 45 s). A run killed anyway, for
  example by a power cut, is marked `INTERRUPTED` at the next start.
* **Docker Desktop must start with Windows:** Settings → General → *Start Docker Desktop when you sign in*.
  Otherwise nothing runs after a reboot until Docker is opened.
