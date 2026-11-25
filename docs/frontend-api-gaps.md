# Frontend API gaps (proposed contracts, not implemented)

The 2026-11-22 frontend enhancement was frontend-only: no backend or forecast-service code was changed.
Where the public API lacked something, the frontend uses only real data from existing endpoints, and the
gaps below are documented for a later backend change.

## 1. City rankings (optional; currently derived client-side)

**Now:** `frontend/src/lib/cities.ts` computes city AQI from `GET /api/v1/stations` (real data): the mean AQI
of the city's stations that are reporting now (not stale), grouped by city **and** state ("Aurangabad"
and "Byrnihat" exist in two states each). Cities with no fresh station are left out.

**Why a backend endpoint would be better:** one consistent definition for every client, fewer bytes
(561 stations today), and categories from `aqi.yml` instead of the frontend's display copy of the bands.

```
GET /api/v1/cities/rankings?order=desc|asc&limit=10
200 [
  { "city": "Delhi", "state": "Delhi", "aqi": 229, "category": "POOR",
    "method": "mean_of_fresh_stations", "stations": 37, "latestTs": "2026-11-22T15:30:00Z" }
]
```

## 2. Health advice (currently a generated copy of the backend rules)

**Now:** the frontend evaluates `forecast-service/advice/rules.yaml`, copied verbatim to
`frontend/src/lib/advice-rules.json` by `frontend/scripts/sync_advice_rules.py`. Its output was checked
against the Python `action_codes()` for all 90 category x profile x trend combinations (0 differences).
The copy must be regenerated whenever `rules.yaml` changes. The forecast service's `/advice` endpoint is
internal (not reachable from the browser) and also calls the LLM, which a page view should not trigger.

```
GET /api/v1/stations/{id}/advice?profile=ASTHMA&language=en      (profile optional; signed-in users default to their saved profile)
200 { "stationId": "...", "aqi": 312, "category": "VERY_POOR", "trend": "WORSENING",
      "healthProfile": "ASTHMA", "actionCodes": ["STAY_INDOORS", "..."],
      "sentences": ["Stay indoors as much as you can.", "..."],
      "disclaimer": "General guidance only, not medical advice.", "source": "RULES" }
```
Template sentences only (no LLM call) so it is cheap and deterministic. `trend` would come from the same
rule the alert engine uses (`trend-delta`), which the frontend currently does not attempt (it uses STABLE).

## 3. Forecast readiness and failure detail

**Now:** readiness counts are re-derived from `/aqi/history` (valid hours in the last 168 h) only to explain
the backend's status; the backend's `status` and `message` are authoritative. `UNAVAILABLE` covers both an
unreachable forecast service and a failed prediction, which the frontend cannot tell apart.

Proposed additions to `GET /api/v1/stations/{id}/forecast`:
```
{
  "status": "UNAVAILABLE",
  "reason": "SERVICE_UNREACHABLE | CIRCUIT_OPEN | PREDICTION_FAILED | REJECTED_BY_SERVICE",
  "readiness": { "validHours": 6, "requiredHours": 24, "windowHours": 168,
                 "latestObservation": "2026-11-22T15:30:00Z", "maxDataAgeMinutes": 180 },
  "lastSuccessfulGeneratedAt": "2026-11-22T14:25:00Z"
}
```
`lastSuccessfulGeneratedAt` would let the page show the last good forecast time even while the service is down.
