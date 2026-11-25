# Degraded-mode demonstration (X8)

Record a screenshot or terminal capture for each step; the expected behaviour is in spec section 10.
The integration tests (`AlertFlowIT`, `IngestionIT`, `RedisDownIT`) check the same behaviours automatically.

| # | Do | Expect |
|---|----|--------|
| 1 | `docker compose stop forecast-service`, then `GET /api/v1/stations/<id>/forecast` | `"status": "UNAVAILABLE"`; Grafana "forecast failures" rises |
| 2 | Same, `GET /api/v1/stations/<id>/aqi/current` | 200 with the current AQI, unaffected |
| 3 | Wait for an alert evaluation (10 min) with a high current AQI | alert of kind `CURRENT` (no forecast) |
| 4 | After ~5 failures, check logs | calls short-circuited by the circuit breaker (no timeout per request) |
| 5 | Unset `GROQ_API_KEY` (or point it at a bad key), trigger an alert | message built from templates, still with the disclaimer |
| 6 | Set `TELEGRAM_BOT_TOKEN` to an invalid value / block network | alerts stay `PENDING`, `attempts` and `next_attempt_at` grow in `/me/alerts` |
| 7 | `docker compose stop redis`, call nearest/current | still 200, slower; WARN "bypassing cache" in logs |
| 8 | Set `DATA_GOV_API_KEY` to garbage, `POST /api/v1/admin/ingestion-runs` | run `FAILED`, key not in the error text; current AQI turns `stale: true` after 3 h |
| 9 | `docker compose stop postgres`, `GET /actuator/health` | `DOWN`; API calls return 503 "Database unavailable" quickly |
