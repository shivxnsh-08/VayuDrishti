# Operating VayuDrishti locally

## Start, stop, update

```bash
cd E:\VayuDrishti
docker compose up -d --build          # first start, or after pulling changes (data volume is kept)
docker compose ps                     # all services "Up"; five show "(healthy)"
docker compose stop                   # stop everything; data is kept; containers stay stopped until started
docker compose start                  # start again
docker compose up -d --build --no-deps backend    # rebuild/redeploy ONE service (backend, frontend, ...)
```

**Never** run `docker compose down -v` or delete the `saans_check_pgdata` volume. That erases all observations,
which cannot be downloaded again.

* **Automatic restart:** every service uses `restart: unless-stopped`. Docker Desktop is set to start when you sign
  in. Together they bring the stack back after a crash or a reboot.
* **Shutdown is graceful:**
  * The backend finishes in-flight requests and a running ingestion (up to 30 s; the compose stop grace is 45 s).
  * A run killed anyway is marked `INTERRUPTED` at the next start.
  * About 20 s after start-up a `RECOVERY` run starts if the last success is older than 10 minutes.

| URL | What |
|---|---|
| http://localhost:5173 | Web app |
| http://127.0.0.1:8080/actuator/health | Backend health |
| http://127.0.0.1:8080/api/v1/ingestion/health | Data-pipeline status (public) |
| http://127.0.0.1:8080/api/v1/model-info | Forecast model / lifecycle status |
| http://127.0.0.1:8000/lifecycle | ML lifecycle detail |
| http://127.0.0.1:3000 | Grafana (admin / `GRAFANA_ADMIN_PASSWORD`) |
| http://127.0.0.1:9090 | Prometheus |

Use `127.0.0.1` rather than `localhost` in command-line tools. On this machine `localhost` resolves to IPv6
first, and the services listen on IPv4.

## Tests

```bash
# Backend: unit + integration (Testcontainers PostgreSQL/PostGIS + WireMock; needs Docker). Low-memory flags:
cd backend
MAVEN_OPTS="-Xmx320m -XX:+UseSerialGC" ./mvnw verify \
  -DargLine="-Xmx448m -XX:MaxMetaspaceSize=224m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=48m"

# Frontend: unit tests (Vitest) + type check and production build
cd frontend && npm test && npm run build

# Forecast service: the pinned dependencies need Python 3.11, so run inside the service image:
docker run --rm -v "E:/VayuDrishti:/repo:ro" --entrypoint sh saans_check-forecast-service -c \
  'mkdir -p /tmp/r/backend/src/main && cp -r /repo/forecast-service /repo/contracts /tmp/r/ && \
   cp -r /repo/backend/src/main/resources /tmp/r/backend/src/main/ && cd /tmp/r/forecast-service && \
   pip install -q pytest==8.3.4 matplotlib==3.10.0 tabulate==0.9.0 && python -m pytest -q'
```

The three `test_repository_pg.py` tests need `PGTEST_DSN` pointing at a **throwaway** PostGIS. They drop the
schema. See the file header, and never point them at the project database.

## Troubleshooting

| Symptom | Cause | What to do |
|---|---|---|
| UI: "Could not load data — The VayuDrishti API is not reachable right now…" (HTTP 502) | The backend is stopped, restarting or still starting (about 12 s). nginx answers 502 when it cannot connect. | Wait: the page retries every 10 s. If it persists, run `docker compose ps` and `docker compose logs --tail 100 backend`. |
| Pipeline card shows **STALE** | No observation newer than 3 h. Usually the stack was off; otherwise the source is behind. | Check the "Last run" status. If runs are `SUCCESS` with 0 new observations, the source is still serving old data. Missed hours cannot be recovered. |
| Pipeline card shows **DEGRADED** | The newest observation is older than 135 min, a run failed or was partial, there was no run for 35 min, or fewer than half the stations are fresh. | Read the reasons on the card. A single transient failure clears on the next run. |
| **SOURCE_UNAVAILABLE** | 2+ consecutive failures on the source side (network, TLS, DNS, 5xx, 429). | Retries already happened. Check data.gov.in status and your network. Ingestion resumes by itself. |
| **FAILED** with category `AUTH` | The data.gov.in key is wrong or expired. | Fix `DATA_GOV_API_KEY` in `.env`, then run `docker compose up -d --no-deps backend`. |
| Forecast: "Only N hourly points available; need 24" | The station has fewer than 24 observed hours in the last 7 days. | Keep the stack running. Forecasts appear automatically. |
| model-info: "No trained model promoted yet" | Readiness needs 672 h of history. | Expected for about 4 weeks of continuous running. Readiness is re-checked every 6 h. |
| Telegram alerts never arrive | `TELEGRAM_BOT_TOKEN` is empty, or the user has not linked the bot. | See "Telegram" below. |
| `curl` in `E:\VayuDrishti` says "Access is denied" | An empty `curl.exe` file in the project folder shadows the real one in `cmd.exe`. | Use `C:\Windows\System32\curl.exe`, or remove the stray file. |

Run history: `docker compose exec postgres psql -U saanscheck -d saanscheck -c "SELECT id, trigger, started_at, status, fetched, inserted, error_category FROM ingestion_runs ORDER BY id DESC LIMIT 10"`.

## Environment (`.env`, never committed)

| Variable | Required | Purpose |
|---|---|---|
| `DATA_GOV_API_KEY` | yes | data.gov.in API key (ingestion) |
| `JWT_SECRET` | yes | ≥ 32 random characters; signs login tokens |
| `POSTGRES_PASSWORD` | yes | database password (backend and forecast-service use it) |
| `GRAFANA_ADMIN_PASSWORD` | recommended | Grafana admin login. Leading or trailing spaces are trimmed by Compose. |
| `ADMIN_EMAILS` | optional | Emails that get the ADMIN role **when they register** (admin API, ingestion alerts) |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` | optional | Telegram alerts (see below) |
| `GROQ_API_KEY`, `GROQ_MODEL` | optional | LLM phrasing of advice; templates are used without it |
| `INGESTION_STATE_FILTER` | optional | Ingest one state only; empty means all of India |
| `CACHE_ENABLED`, `API_DOCS_PUBLIC`, `LIFECYCLE_ENABLED` | optional | feature switches (default true) |

After editing `.env`, run `docker compose up -d --no-deps <service>` to apply it. Compose recreates the container
when its environment changed; data is kept.

### Telegram

How it works:
* The backend **polls** the Bot API (`getUpdates` every 5 s), so no public HTTPS webhook is needed. Only one
  process may poll a bot: a second backend, or a webhook set on the bot, makes Telegram answer HTTP 409. The
  backend logs that as a WARN.
* Chat ids stay on the server. The API only says `linked: true/false`.
* Link codes:
  * random, 8 characters (about 40 bits)
  * valid for 15 minutes and usable once
  * at most 5 per account per 15 minutes
* A chat that sends 5 wrong codes is blocked for 15 minutes.
* One chat can belong to only one account, and only private chats are accepted.
* Alert delivery:
  * retries with exponential backoff (and Telegram's own `retry_after` on HTTP 429), at most 3 attempts
  * is never repeated when the outcome is unknown (no duplicates)
  * is recorded per alert in the `alerts` table (status, attempts, last error) and shown on the Alerts page

#### 1. Create the bot (@BotFather)
1. In Telegram, open a chat with **@BotFather**. Check the blue verified tick: it is Telegram's official bot.
2. Send `/newbot`. Give it a display name (for example `VayuDrishti Alerts`), then a **username** that ends in
   `bot` (for example `VayuDrishtiAlertsBot`).
3. Optional: `/setdescription` and `/setuserpic`. Do **not** set a webhook (`/setwebhook`): it would stop polling.

#### 2. The bot token
BotFather's reply to `/newbot` contains the HTTP API token, in the form `123456789:AA...` (digits, a colon, 35
characters). Treat it like a password:
* Never paste it into chats, issues, screenshots or code.
* If you lose it, use `/token` in @BotFather (choose your bot) to see it again.

#### 3. The bot username
This is the name you chose in step 1, without `@` (it ends in `bot`). It is also shown in `/mybots` in @BotFather.
The website builds the "Open Telegram" link from it. At start-up, the backend checks it against the token with
`getMe` and logs a WARN if they belong to different bots.

#### 4. Configure `.env`
Edit `E:\VayuDrishti\.env` (git-ignored; never commit it) and set:

```
TELEGRAM_BOT_TOKEN=<token from step 2>
TELEGRAM_BOT_USERNAME=<username from step 3, without @>
ADMIN_EMAILS=<your email, for ingestion-health alerts; comma-separated for several>
```

* No quotes and no spaces around `=`.
* `ADMIN_EMAILS` grants the ADMIN role only when an account with that email **registers**. Existing accounts are
  not changed.

#### 5. Apply the configuration (safe restart)

```bash
docker compose up -d --no-deps backend     # recreates only the backend with the new environment; data is kept
docker compose logs --tail 50 backend | findstr /i telegram
```

The log must show `Telegram bot verified as @<your bot>`.
* `Telegram rejected TELEGRAM_BOT_TOKEN` means the token is wrong.
* `TELEGRAM_BOT_USERNAME is '...' but the token belongs to @...` means the username is wrong.
* `Telegram is not configured` means the token is empty.

A restart takes about 15 s. The website shows "API is not reachable" during that time and recovers by itself.
To avoid restarting during a scheduled ingestion (:05, :20, :35, :50 UTC), restart a few minutes after one.

#### 6. Link a Telegram account
1. Sign in on the website (http://localhost:5173), open **Profile**, and save a station.
2. In **Telegram alerts**, press **Generate link code**. You get an 8-character code with a countdown.
3. Press **Open Telegram**, then **Start** in the bot. If the code is not filled in automatically, send
   `/start CODE`.
4. The bot answers "Linked". Within a few seconds the website shows **Connected**.

Bot commands: `/start CODE` (link), `/help`, `/status` (is this chat connected?), `/unlink`.

#### 7. Send a test notification
On **Profile → Telegram alerts → Send test message** (at most 3 per 10 minutes). It goes only to your own linked
chat. Real alerts are sent when your station's current or forecast AQI reaches the alert level in your profile,
outside your quiet hours. They are checked every 10 minutes, with a cooldown, and re-sent only when the category
gets worse.

#### 8. Unlink
Either **Profile → Telegram alerts → Unlink Telegram**, or send `/unlink` to the bot. Both stop alerts at once.

#### 9. Troubleshooting

| Symptom | Fix |
|---|---|
| Profile says "Telegram is not set up on this server" | `TELEGRAM_BOT_TOKEN` is empty: step 4, then step 5 |
| "Telegram bot unavailable" / log `rejected TELEGRAM_BOT_TOKEN` | Wrong or revoked token: copy it again from @BotFather (`/token`) |
| Bot never answers `/start` | Log shows `getUpdates conflict (HTTP 409)`: another program uses the same bot, or a webhook is set. Remove the webhook (in a browser, open `https://api.telegram.org/bot<TOKEN>/deleteWebhook`) and stop the other program. |
| "That code is invalid or expired" | Codes last 15 minutes and work once: generate a new one |
| "Too many invalid codes" | Wait 15 minutes |
| "already connected to another VayuDrishti account" | Send `/unlink` in that chat first, or use the other account |
| Test message fails (502) | You blocked the bot: unblock it in Telegram, or unlink and link again |
| Alerts show FAILED on the Alerts page | The reason is in the alert's error. PERMANENT means blocked bot or invalid chat. UNKNOWN means a timeout: not resent, to avoid a duplicate. |

#### 10. Rotate a compromised token
1. In @BotFather: `/revoke` and choose the bot. The old token stops working immediately, and BotFather shows a new
   one.
2. Put the new token in `.env` (`TELEGRAM_BOT_TOKEN`) and run step 5.
3. Linked chats stay linked: the chat ids belong to the bot, not to the token.
4. Check the log line `Telegram bot verified`.

## Morning AQI briefing (Telegram)

* **Who and when:** every user with Telegram linked and a saved station gets **one briefing per day**, by default
  at **07:00 Asia/Kolkata**. To turn it off or change the time: **Profile → Edit profile → Morning AQI briefing**.
  It uses the profile's alert language and monitoring station.
* **Content:**
  * the latest genuine reading: AQI, category, main pollutant, and its observation time and age (marked
    **STALE** if older than 3 h, with no health advice then)
  * health advice for the user's health profile, from the same advice service and templates as alerts
  * today's forecast, labelled baseline or ML model; "not available (reason)" when there isn't one
  * a dashboard link, set with `APP_URL` (default http://localhost:5173)
  * if there is no reading at all, the message says so instead of reporting an AQI
* **Scheduling:** a single job checks every minute. If the backend was down at delivery time, the briefing is
  still sent up to **3 h late** (`saanscheck.briefing.catch-up-window`); after that, the day is skipped.
* **No duplicates:** the key `brief:{user}:{local date}` is unique in the `alerts` table, so restarts, retries and
  setting changes cannot produce a second message.
* **Delivery:** handled by the alert dispatcher (retries with backoff, Telegram rate limits, never resent when the
  outcome is unknown). A briefing that cannot be delivered within the window is marked `FAILED` ("expired").
* **History:** each briefing appears in the history (Alerts page and `GET /api/v1/me/alerts`) as `MORNING_BRIEFING`,
  with the exact text sent, `scheduledFor`, `sentAt`, status and any error. The data it was built from is in
  `alerts.context`.
* Briefings never count toward alert cooldown or escalation.
* **Switch off for everyone:** `BRIEFING_ENABLED=false` in `.env`, then `docker compose up -d --no-deps backend`.

## Forecast page: nearest station

* **Location:**
  * If the browser has already allowed location, the page selects the nearest **eligible** station automatically:
    fresh data, plus 24 valid hours in the last 7 days for a forecast.
  * Closer stations that were skipped are listed with the reason, such as stale data or too little history.
  * If no station within 50 km can be forecast yet, it shows the nearest station with current data and says that
    its forecast is not ready.
* **Privacy:**
  * The page explains why location is useful and asks only when the user clicks **Use my location**.
  * It never asks again after a refusal.
  * Coordinates are rounded to about 1 km, are only sent to the VayuDrishti backend to find the station, and are
    not stored.
* **Fallbacks:** without location, the page uses the Profile's saved station. Otherwise it asks the user to search.
* **Manual choice:** search by city or station, or a link with `?station=`. This wins until **Back to my nearest
  station** is clicked.
* **Updates:**
  * Data and forecast refresh every 5 minutes, with a "last updated" time.
  * Location is re-checked every 5 minutes while the tab is visible.
  * The station only changes after a move of more than 2 km, or if it becomes unsuitable.

## AQI alerts (change-based)

* **Switch:** on or off with **Profile → Edit profile → AQI alerts on Telegram**. The morning briefing has its own
  switch.
* **Detection:** new readings are checked every **2 minutes** (`saanscheck.alerts.evaluation-interval`). Each
  observation is processed exactly once per user.
* **Notifications** (the "reference" is the AQI the user was last told about):
  * **Alert level reached**: the AQI goes from below to at or above the user's alert level.
  * **AQI deterioration**: at or above the level, and a worse category or at least **25** points higher than the
    reference (`saanscheck.alerts.events.significant-change`).
  * **Air quality improved**: a better category, at least 25 points lower, or falling below the alert level.
  * **8-hour reminder**: the AQI stays at or above the level without a significant change. Sent 8 h after the last
    event or reminder (`reminder-interval`), then every 8 h; any event restarts the timer. Never sent below the level.
  * Changes entirely below the user's level are not notified.
* **Stale data:** readings older than 3 h never produce alerts, reminders or advice. A separate "No recent data"
  notice is sent at most once a day.
* **Quiet hours:**
  * Reminders wait until the quiet period ends.
  * Routine updates (improvements, ordinary deteriorations) are recorded as "Held: quiet hours", not sent. If the
    change still holds at the next reading after quiet hours, it is announced then.
  * Only **critical** alerts (category ≥ `critical-category`, default SEVERE) are sent during quiet hours, and only
    if the user ticked "Send Severe alerts even during quiet hours" and `critical-bypasses-quiet-hours` is true.
* **Forecast alerts** use the existing forecast rules (6-hour cooldown, escalation).
* **Delivery:** all messages are Telegram HTML (escaped, at most 4000 characters). If Telegram rejects the markup,
  the same text is sent once as plain text.
* **History:** every notification is stored with its type, exact text, AQI and category, previous AQI and change,
  observation time, send time, status and error. It appears on the Alerts page and in `GET /api/v1/me/alerts`.

## Administrator account

* **Setup:** set `ADMIN_EMAIL` and `ADMIN_PASSWORD` in `.env`, then run
  `docker compose up -d --no-deps backend`. At start-up the backend creates that account with role ADMIN,
  storing a BCrypt hash; the password is never logged.
* **Signing in:** the administrator uses the normal **Sign in** form and is taken to the admin interface. It shows
  only Admin Dashboard and User Management; every other URL redirects there.
* **Restarts and duplicates:** restarts never create duplicates and never overwrite the password.
* **Changing the password:** set the new `ADMIN_PASSWORD` plus `ADMIN_PASSWORD_RESET=true`, restart once, then set
  the flag back to `false`.
* **If `ADMIN_EMAIL` already belongs to a normal account:** it is promoted only when `ADMIN_PASSWORD` equals that
  account's own password (proof of ownership). Otherwise nothing changes and the log says why. Use a dedicated
  email: an admin account no longer sees the normal app (Telegram, profile, alerts).
* **Changing `ADMIN_EMAIL`:** creates the new admin. The old one keeps its role and is reported in the log.
* **Weak passwords:** a weak or missing password is refused. With `ADMIN_REQUIRED=true` (production), start-up
  fails instead.
* **Access control:** all `/api/v1/admin/**` endpoints require the ADMIN role in the signed token. Anonymous callers
  get 401, normal users 403.
* **User Management:** read-only. It shows ID, email, registration date, role, status (derived from recorded
  sign-ins), station, Telegram linked yes/no, and last login.
  * Names are not collected by VayuDrishti.
  * Passwords, hashes, tokens, Telegram chat ids and coordinates are never returned.
  * There is no API to change roles or delete accounts.
