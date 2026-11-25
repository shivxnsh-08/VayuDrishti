# AQI standard and health advice: verification status

Last checked 2026-11-22. "Verified" means checked against a source named here. Everything else is an
assumption, kept in configuration (`backend/src/main/resources/aqi.yml`,
`forecast-service/config/aqi_breakpoints.yaml`, `forecast-service/advice/rules.yaml`) and pinned by tests,
so that a correction is a config edit with failing tests pointing at every affected value.

## Sources attempted

| Source | Result |
|---|---|
| cpcb.nic.in National AQI page and "About AQI" PDF | Not reachable from this environment (TLS certificate rejected) |
| PIB press release on the 2014 AQI launch | HTTP 403 |
| India Environment Portal copy of the CPCB AQI report | HTTP 404 |
| app.cpcbccr.com "About National Air Quality Index (AQI)" | Reachable, but the same host states it is *not affiliated with or endorsed by CPCB*; treated as secondary |

**No primary CPCB document could be read.** Before reporting results, check the items below against the
CPCB National AQI report / notification and record the document and page here.

## Status by item

| Item | Status | Evidence |
|---|---|---|
| Category names and ranges (Good 0-50, Satisfactory 51-100, Moderate 101-200, Poor 201-300, Very Poor 301-400, Severe 401-500) | Matches a **secondary** source only | app.cpcbccr.com page above |
| Feed values are per-pollutant **sub-indices**, not concentrations (`values-are-sub-indices: true`) | **Verified empirically** from the real feed (3,906 records, 2026-11-22) | All 3,598 numeric values are integers, none exceed 500, PM2.5/PM10 `max_value` reaches exactly 500 (the index cap), CO averages reach 143 (impossible as mg/m³). The feed's `min <= avg <= max` held for every record. |
| Overall AQI = highest sub-index | Matches a secondary source | "The highest relevant sub-index is used to represent overall air quality" (app.cpcbccr.com) |
| Minimum 3 pollutants, one of PM2.5/PM10 | **Not verified** | Assumption in `aqi.yml` |
| Concentration breakpoint table (all pollutants) | **Not verified** | Only used for the historical backfill (live data arrives as sub-indices) |
| Averaging periods (24 h PM/NO2/SO2/NH3, 8 h CO/O3) and 16-of-24 h coverage | **Not verified** | Assumption in `aqi_breakpoints.yaml`, backfill only |
| Linear interpolation inside a band; cap at 500 above the table | **Not verified** | Implementation choice, identical in Java and Python |
| Default alert thresholds per health profile (sensitive groups at MODERATE, GENERAL at POOR) | Consistent with the secondary source's statements | Moderate: "Breathing discomfort to people with lung disease, asthma, children, and older adults"; "Sensitive groups should reduce prolonged exertion outdoors" |
| Advice rules (`rules.yaml`) | Directionally consistent with the secondary source; **not verified** against a public-health authority | e.g. MODERATE + sensitive -> REDUCE_PROLONGED_EXERTION; POOR -> limit outdoor activity; SEVERE -> stay indoors |

## Tests that pin the configuration

- `backend/.../AqiBoundaryTest` reads the real `aqi.yml`: every band edge, every breakpoint row's two ends,
  contiguity, alignment of breakpoint index ranges with bands, the cap above the table, and the overall rule.
- `backend/.../AqiCalculatorTest`, `forecast-service/tests/test_backfill.py::test_sub_index_boundaries_match_backend`:
  the same boundary values on both sides of the service boundary.
- `test_backfill.py::test_breakpoints_match_backend_config` fails if the Python copy of the breakpoints drifts
  from the backend file.
- `RealPayloadMapperTest` runs a sanitised real payload through the mapper.

## Safety posture

Advice is conservative and non-clinical: it never mentions medication, doses or diagnoses (the LLM output
validator rejects such wording) and every message ends with "General guidance only, not medical advice."
