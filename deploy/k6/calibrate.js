// Calibration (Phase 006): can upstream-sim sustain the rates the bench needs, alone?
// Open model (constant-arrival-rate): k6 sends at the target rate whether or not responses are slow,
// so queueing shows up as latency and dropped iterations instead of being hidden (coordinated omission).
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 500);
const TARGET = __ENV.TARGET_URL || 'http://upstream-sim:15201/stock/sku-1';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    open: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '20s',
      preAllocatedVUs: Math.min(2000, Math.max(50, RATE)),
      maxVUs: 4000,
    },
  },
  summaryTrendStats: ['p(50)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(TARGET);
  check(res, { 'status 200': (r) => r.status === 200 });
}
