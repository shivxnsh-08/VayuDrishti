// Load test for the two hot read endpoints (experiment X7).
// Run once with CACHE_ENABLED=true and once with CACHE_ENABLED=false (restart the backend in between):
//   k6 run -e BASE_URL=http://localhost:8080 -e STATION_ID=<a real station id> load/k6-api.js
// Compare p50/p95 from the k6 summary, and the saanscheck_api_latency metric tagged cache=on/off.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const STATION = __ENV.STATION_ID || 'ito-delhi-cpcb';
// A handful of fixed points so the nearest-station cache can actually hit (keys are rounded to ~1 km).
const POINTS = [[28.63, 77.22], [28.61, 77.23], [28.65, 77.30], [28.57, 77.32], [28.70, 77.10]];

export const options = {
  scenarios: {
    current_aqi: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '2m',
                   preAllocatedVUs: 20, exec: 'currentAqi' },
    nearest:     { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '2m',
                   preAllocatedVUs: 20, exec: 'nearest' },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{scenario:current_aqi}': ['p(95)<500'],
    'http_req_duration{scenario:nearest}': ['p(95)<500'],
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'avg', 'max'],
};

export function currentAqi() {
  const r = http.get(`${BASE}/api/v1/stations/${STATION}/aqi/current`);
  check(r, { 'status 200': (res) => res.status === 200 });
}

export function nearest() {
  const [lat, lon] = POINTS[Math.floor(Math.random() * POINTS.length)];
  const r = http.get(`${BASE}/api/v1/stations/nearest?lat=${lat}&lon=${lon}&limit=3`);
  check(r, { 'status 200': (res) => res.status === 200 });
}
