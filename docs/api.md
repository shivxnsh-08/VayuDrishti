# API examples

Base URL `http://localhost:8080`. Station reads are public; `/me/**` needs a bearer token; `/admin/**` needs ADMIN
(the dedicated admin account from `ADMIN_EMAIL` / `ADMIN_PASSWORD`, see [operations.md](operations.md); emails listed
in `ADMIN_EMAILS` also get ADMIN when they register). The role is read from the database on every request.

```bash
# Register / login -> {"accessToken": "...", "tokenType": "Bearer", "expiresAt": "...", "role": "USER"}
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
     -d '{"email":"me@example.com","password":"a-long-password"}'
TOKEN=...   # accessToken from the response

# All active stations with latest AQI and stale flag (public; map/dashboard)
curl -s localhost:8080/api/v1/stations

# Model in use and its validation scores (public view of the forecast service's /model-info)
curl -s localhost:8080/api/v1/model-info

# Nearest stations (public, cached)
curl -s 'localhost:8080/api/v1/stations/nearest?lat=28.63&lon=77.22&limit=3'

# Current AQI with stale flag
curl -s localhost:8080/api/v1/stations/<id>/aqi/current

# History with data-quality flags (paged)
curl -s 'localhost:8080/api/v1/stations/<id>/aqi/history?from=2026-11-01T00:00:00Z&page=0&size=100'

# 24 h forecast: status AVAILABLE | UNAVAILABLE | INSUFFICIENT_DATA | STALE_DATA, method MODEL or BASELINE_*
curl -s localhost:8080/api/v1/stations/<id>/forecast

# Profile (coarse location; nearest station picked automatically when stationId is omitted)
curl -s -X PUT localhost:8080/api/v1/me/profile -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"healthProfile":"ASTHMA","latitude":28.6139,"longitude":77.2090,"language":"hi",
          "quietStart":"22:00","quietEnd":"07:00","severeOverridesQuiet":true}'
curl -s localhost:8080/api/v1/me/profile -H "Authorization: Bearer $TOKEN"

# Morning briefing and AQI event alerts are profile settings on the same PUT. Location, station, language and
# quiet hours are replaced on every PUT, so send the full profile; these three fields keep their current value
# when omitted or null. morningBriefingTime is local time in Asia/Kolkata (default 07:00); aqiAlertsEnabled
# switches AQI event alerts and their 8-hour reminders.
curl -s -X PUT localhost:8080/api/v1/me/profile -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"healthProfile":"ASTHMA","latitude":28.6139,"longitude":77.2090,"language":"hi",
          "quietStart":"22:00","quietEnd":"07:00","severeOverridesQuiet":true,
          "morningBriefingEnabled":true,"morningBriefingTime":"07:30","aqiAlertsEnabled":true}'

# Telegram linking: send the code (or open deepLink) in the bot chat
curl -s -X POST localhost:8080/api/v1/me/telegram/link -H "Authorization: Bearer $TOKEN"

# Alert history with status (PENDING | SENDING | SENT | FAILED | SUPPRESSED)
curl -s localhost:8080/api/v1/me/alerts -H "Authorization: Bearer $TOKEN"

# Delete account and personal data
curl -s -X DELETE localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"

# Public: data-pipeline health (HEALTHY/DEGRADED/STALE/FAILED/SOURCE_UNAVAILABLE/UNKNOWN + reasons)
curl -s localhost:8080/api/v1/ingestion/health

# Admin: ingestion runs + data-quality summary; POST triggers a run now (409 if a run is in progress)
curl -s localhost:8080/api/v1/admin/ingestion-runs -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s -X POST localhost:8080/api/v1/admin/ingestion-runs -H "Authorization: Bearer $ADMIN_TOKEN"

# Admin: user management (read-only by design: no endpoint changes roles or deletes accounts).
# List: optional search (email contains), role=ADMIN|USER, status=ACTIVE|INACTIVE|NO_LOGIN_RECORDED
# (ACTIVE = signed in within 30 days), sort=id|email|role|createdAt|lastLoginAt, dir=asc|desc, page, size (max 100)
# -> {"items": [{id, name, email, role, status, createdAt, lastLoginAt, stationId, stationName, telegramLinked}],
#     "total", "page", "size"}
curl -s 'localhost:8080/api/v1/admin/users?search=example&role=USER&status=ACTIVE&sort=lastLoginAt&dir=desc&page=0&size=20' \
     -H "Authorization: Bearer $ADMIN_TOKEN"
# Totals, admin/normal accounts, active in 30 days, no login recorded, registrations in 7/30 days, 5 newest accounts
curl -s localhost:8080/api/v1/admin/users/stats -H "Authorization: Bearer $ADMIN_TOKEN"
# One account: the list row plus health profile, language, alert threshold, quiet hours, AQI-alert and
# morning-briefing settings, notification count and last notification time
curl -s localhost:8080/api/v1/admin/users/<id> -H "Authorization: Bearer $ADMIN_TOKEN"

# Admin: ML lifecycle (read-only): lifecycle state, recent training runs (runs=1..200, default 20), lifecycle
# events (events=1..500, default 50), and the forecast service's live /model-info when it is reachable
# (forecastServiceAvailable=false otherwise)
curl -s 'localhost:8080/api/v1/admin/ml/lifecycle?runs=20&events=50' -H "Authorization: Bearer $ADMIN_TOKEN"
```

Internal (forecast service, port 8000): `POST /forecast`, `POST /advice`, `GET /health`, `GET /model-info`;
interactive docs at `/docs`. Example payloads: [`contracts/`](../contracts/).

Errors are RFC 7807 problem documents (`{"status": 404, "detail": "Unknown station: x", ...}`).
