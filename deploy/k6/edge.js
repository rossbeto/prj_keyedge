// Load through the edge sidecar (Phase 010 native-vs-jvm; Phase 015 bench). Open model.
// KEYS: comma-separated API keys, used round-robin (each key's default limit is 50 req/s).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// per-status counts so a summary shows WHY requests failed (429 limit, 503 shed/unavailable, ...)
const byStatus = { 200: new Counter('status_200'), 429: new Counter('status_429'), 503: new Counter('status_503') };
const other = new Counter('status_other');
const shed = new Counter('status_503_overloaded');

const RATE = Number(__ENV.RATE || 200);
const KEYS = (__ENV.KEYS || '').split(',').filter((k) => k.length > 0);
const TARGET = __ENV.TARGET_URL || 'http://127.0.0.1:15080/v1/quotes/sku-1?qty=1';

export const options = {
  scenarios: {
    open: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(1000, Math.max(50, RATE)),
      maxVUs: 2000,
    },
  },
  summaryTrendStats: ['p(50)', 'p(99)', 'max'],
};

export default function () {
  const key = KEYS[(__VU * 7919 + __ITER) % KEYS.length];
  const res = http.get(TARGET, { headers: { Authorization: `Bearer ${key}` } });
  (byStatus[res.status] || other).add(1);
  if (res.status === 503 && String(res.body).includes('overloaded')) {
    shed.add(1); // the edge's concurrency gate, not an unavailable upstream
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}
