// The bench load (Phase 015): open model (constant-arrival-rate), so a slow system cannot slow the
// generator down and hide queueing (coordinated omission). Two targets:
//   MODE=edge    through the sidecar: Authorization: Bearer <key>, keys round-robin (50 req/s each by default)
//   MODE=direct  straight to quotes inside the edge's network namespace: the signed principal the edge would send
//                (X-Keyedge-Principal + -Signature, HMAC-SHA256 with the principal key; D-26), no sidecar on the path
// Secrets come in through the environment of this one container and are never logged.
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter } from 'k6/metrics';

const shed = new Counter('status_503_overloaded');
const RATE = Number(__ENV.RATE || 20);
const MODE = __ENV.MODE || 'edge';
const URL = __ENV.TARGET_URL;
const PATH = __ENV.SIGN_PATH || '';
const KEYS = (__ENV.KEYS || '').split(',').filter((k) => k.length > 0);
const PRINCIPAL_KEY = MODE === 'direct' ? encoding.b64decode(__ENV.PRINCIPAL_KEY || '', 'std') : null;
const PRINCIPAL_ID = __ENV.PRINCIPAL_ID || ''; // tenant|keyId|scopes

export const options = {
  discardResponseBodies: false,
  scenarios: {
    open: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '10s',
      preAllocatedVUs: Math.min(1500, Math.max(20, Math.ceil(RATE / 4))),
      maxVUs: Math.max(100, RATE * 2),
    },
  },
  summaryTrendStats: ['p(50)', 'p(90)', 'p(99)', 'max'],
};

function headers() {
  if (MODE === 'direct') {
    const principal = encoding.b64encode(`${PRINCIPAL_ID}|${Math.floor(Date.now() / 1000)}`, 'rawurl');
    const signature = crypto.hmac('sha256', PRINCIPAL_KEY, `${principal}|GET|${PATH}`, 'base64rawurl');
    return { 'X-Keyedge-Principal': principal, 'X-Keyedge-Principal-Signature': signature };
  }
  return { Authorization: `Bearer ${KEYS[(__VU * 7919 + __ITER) % KEYS.length]}` };
}

export default function () {
  const res = http.get(URL, { headers: headers(), timeout: '5s' });
  if (res.status === 503 && String(res.body).includes('overloaded')) {
    shed.add(1); // the edge's concurrency gate, not an unavailable upstream
  }
}
