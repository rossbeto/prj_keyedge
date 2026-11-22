# 7. Sidecar → control plane: signed requests that carry the secret

Date: 2026-11-23 · Status: accepted

## Context
The sidecar must ask the control plane whether a presented key is valid. Peppers must stay with the control
plane (a sidecar compromise must not expose them).

## Decision
`POST /internal/v1/verify {keyId, secret}`: the control plane hashes the secret with the pepper. Requests are
HMAC-signed (method, path, timestamp, SHA-256 of the body) with a per-caller credential from Secrets Manager;
30 s skew window and a replay cache of seen signatures. Every failure is the same 401. The secret is never logged.

## Consequences
- The signature protects integrity and freshness, **not confidentiality**: the internal hop must be TLS outside a
  lab (ECS Service Connect TLS or mTLS); recorded in the threat model.
- Rejected verdicts carry a 5 s TTL hint, valid ones 60 s (capped at expiry or the end of a rotation grace period).
