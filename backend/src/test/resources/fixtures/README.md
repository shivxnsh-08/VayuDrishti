# Test fixtures

| File | Origin |
|---|---|
| `datagov_real_20261122_sample.json` | **Real** response from data.gov.in resource `3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69`, fetched 2026-11-22 (snapshot `last_update` 22-11-2026 18:00:00 IST). Sanitised: the API key is never part of the response body (checked), and only 4 stations were kept, with `total`/`count`/`limit` adjusted to the subset. Chosen to include a station name with surrounding whitespace and `NA` values. Envelope fields are unchanged. |
| `datagov_page1.json` | Illustrative, hand-written in the same shape (used for edge cases the real sample lacks: negative value, station without coordinates). |

Observed facts from the full real response (3,906 records, 558 stations), used to configure the mapper:
field names `station`, `last_update`, `latitude`, `longitude`, `pollutant_id`, `min_value`, `max_value`, `avg_value`;
timestamp format `dd-MM-yyyy HH:mm:ss` (IST); one `last_update` for the whole snapshot; pollutant ids
`PM2.5, PM10, NO2, SO2, CO, OZONE, NH3`; missing values are the string `NA` (308 records); all values are strings.
Without a sort parameter, offset pagination returned overlapping pages (58 duplicated, 58 missing records).
