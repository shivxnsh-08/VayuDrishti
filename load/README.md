# Load tests (X7: API latency with and without Redis)

Requires [k6](https://k6.io/) and a running stack with some ingested data.

```bash
# 1. With cache (default)
docker compose up -d
k6 run -e STATION_ID=<station id from /api/v1/stations/nearest> load/k6-api.js | tee load/with-cache.txt

# 2. Without cache
CACHE_ENABLED=false docker compose up -d backend
k6 run -e STATION_ID=<same id> load/k6-api.js | tee load/without-cache.txt
```

Report p50/p95 for both endpoints from both runs, plus the machine you ran on (CPU, RAM, OS).
Grafana's "API latency" panel shows the same split via the `cache` tag.
Warm the cache with one short run first, and run each scenario more than once; quote the median run.
