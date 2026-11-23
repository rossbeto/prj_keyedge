"""Walk through the running stack (make up): partner token -> create key -> replay -> sidecar verify.

    make demo [TARGET=floci|aws-emulator]
Reads the sidecar credential from the emulator (Secrets Manager) to sign internal calls, exactly as the
edge sidecar will (Phase 007). Prints steps and statuses, never the secret part of the key.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

import boto3

assert os.environ.get("AWS_ENDPOINT_URL"), "run through `make demo` (emulator environment)"
KEYS = os.environ.get("KEYS_URL", "http://127.0.0.1:15100")
KEYS_VIA_TOXIPROXY = os.environ.get("KEYS_PROXY_URL", "http://127.0.0.1:15214")
ISSUER = os.environ.get("ISSUER_URL", "http://127.0.0.1:15110")


def call(method: str, url: str, body: object | None = None, headers: dict[str, str] | None = None) -> tuple[int, dict, dict]:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read()
            return r.status, dict(r.headers), json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, dict(e.headers), json.loads(raw) if raw else {}


def signed(base: str, path: str, body: object, credential: bytes) -> tuple[int, dict, dict]:
    raw = json.dumps(body).encode()
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    to_sign = f"POST\n{path}\n{ts}\n{nonce}\n{hashlib.sha256(raw).hexdigest()}".encode()
    sig = base64.urlsafe_b64encode(hmac.new(credential, to_sign, hashlib.sha256).digest()).rstrip(b"=").decode()
    req = urllib.request.Request(base + path, data=raw, method="POST", headers={
        "Content-Type": "application/json", "X-Keyedge-Caller": "edge-quotes",
        "X-Keyedge-Timestamp": ts, "X-Keyedge-Nonce": nonce, "X-Keyedge-Signature": sig})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            out = r.read()
            return r.status, dict(r.headers), json.loads(out) if out else {}
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), json.loads(e.read() or b"{}")


def main() -> int:
    status, _, tok = call("POST", ISSUER + "/token", {"tenant": "acme", "scopes": ["keys:write", "keys:read"]})
    print(f"1. partner token from the issuer: HTTP {status}")
    auth = {"Authorization": "Bearer " + tok["accessToken"]}
    idem = "demo-" + uuid.uuid4().hex[:8]
    status, headers, created = call("POST", KEYS + "/v1/keys", {"name": "demo", "scopes": ["quotes:read", "quotes:reserve"]}, {**auth, "Idempotency-Key": idem})
    key = created["key"]
    print(f"2. create key: HTTP {status}, Location {headers.get('Location')}, ETag {headers.get('ETag')}, key {key[:34]}… ({len(key)} chars, shown once)")
    status, headers, replay = call("POST", KEYS + "/v1/keys", {"name": "demo", "scopes": ["quotes:read", "quotes:reserve"]}, {**auth, "Idempotency-Key": idem})
    print(f"3. same Idempotency-Key again: HTTP {status}, Idempotent-Replayed={headers.get('Idempotent-Replayed')}, secret returned: {'key' in replay}")
    credential = base64.b64decode(json.loads(boto3.client("secretsmanager").get_secret_value(SecretId="keyedge/callers")["SecretString"])["edge-quotes"])
    key_id, secret = key[8:34], key[35:78]
    status, _, verdict = signed(KEYS, "/internal/v1/verify", {"keyId": key_id, "secret": secret}, credential)
    print(f"4. sidecar verify (signed): HTTP {status}, verdict {verdict.get('verdict')}, scopes {verdict.get('scopes')}, ttl {verdict.get('ttlSeconds')} s")
    status, _, verdict = signed(KEYS_VIA_TOXIPROXY, "/internal/v1/verify", {"keyId": key_id, "secret": "A" * 43}, credential)
    print(f"5. wrong secret, through Toxiproxy: HTTP {status}, verdict {verdict.get('verdict')}, ttl {verdict.get('ttlSeconds')} s")
    status, _, _ = signed(KEYS, "/internal/v1/usage", [{"tenant": "acme", "keyId": key_id, "minute": time.strftime("%Y-%m-%dT%H:%M:00Z", time.gmtime()), "allowed": 3, "rejected": 1}], credential)
    print(f"6. usage batch: HTTP {status}")
    edge_ok = True
    if os.environ.get("EDGE_URL", "http://127.0.0.1:15080"):
        edge = os.environ.get("EDGE_URL", "http://127.0.0.1:15080")
        for path in ("/v1/quotes/sku-1", "/v1/quotes-reactive/sku-1"):  # warm-up: a cold JVM can miss fx's 150 ms on its first call
            call("GET", edge + path + "?qty=3&currency=USD", None, {"Authorization": "Bearer " + key})
        status, _, quote = call("GET", edge + "/v1/quotes/sku-1?qty=3&currency=USD", None, {"Authorization": "Bearer " + key})
        print(f"7. through the edge sidecar to quotes: HTTP {status}, tenant {quote.get('tenant')} (the app saw a signed principal, not the key)")
        print(f"   quote: {quote.get('quantity')} x {quote.get('unitPriceCents')} = {quote.get('totalCents')} {quote.get('currency')}, available {quote.get('available')}, warnings {quote.get('warnings')}")
        r_status, _, twin = call("GET", edge + "/v1/quotes-reactive/sku-1?qty=3&currency=USD", None, {"Authorization": "Bearer " + key})
        print(f"   Reactor twin: HTTP {r_status}, same body: {twin == quote}")
        s_status, _, reservation = call("POST", edge + "/v1/quotes/sku-1/reserve?qty=1", {}, {"Authorization": "Bearer " + key})
        print(f"   reserve (non-idempotent POST, never retried): HTTP {s_status} {reservation.get('reservation', '')[:2]}…")
        edge_ok = r_status == 200 and twin == quote and s_status == 201
        status, _, problem = call("GET", edge + "/v1/quotes/sku-1", None, {"Authorization": "Bearer " + key[:-1] + ("A" if key[-1] != "A" else "B")})
        print(f"8. one character changed: HTTP {status} {problem.get('type')} (rejected by the checksum, no call to keys)")
        edge_ok = edge_ok and quote.get("tenant") == "acme" and status == 401
    status, _, revoked = call("POST", KEYS + f"/v1/keys/{key_id}/revoke", {}, auth)
    _, _, verdict = signed(KEYS, "/internal/v1/verify", {"keyId": key_id, "secret": secret}, credential)
    print(f"9. revoke: HTTP {status}, status {revoked.get('status')}; verify now: {verdict.get('verdict')}")
    status, _, _ = call("GET", os.environ.get("EDGE_URL", "http://127.0.0.1:15080") + "/v1/quotes/sku-1", None, {"Authorization": "Bearer " + key})
    print(f"10. edge right after the revoke: HTTP {status} — a cached verdict lives up to its TTL hint (60 s): revocation latency is bounded, not zero (ADR 0009)")
    ok = verdict.get("verdict") == "REVOKED" and edge_ok
    print("demo: OK" if ok else "demo: FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
