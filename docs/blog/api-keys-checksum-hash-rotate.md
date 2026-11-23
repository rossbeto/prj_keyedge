# API keys: checksum, hash, rotate

*Draft. From the keyedge lab; every number links to its results file.*

An API key is a password that a program types. Three decisions shape how safely you can issue one.

## 1. Make a typo cheap to reject

A keyedge key looks like `ke_live_<keyId>_<secret>_<crc>`. The prefix says what it is (secret scanners find it),
the key id says where to look it up, and a CRC32 checksum over the rest lets the sidecar reject a mistyped or
truncated key **without any lookup**. That matters at the edge: garbage never reaches the control plane, and a
flood of random keys costs a few CPU cycles each instead of a database read.

## 2. Store something useless to a thief

The control plane never stores the secret. It stores `HMAC-SHA256(secret, pepper)`, and the pepper lives in
Secrets Manager, not in the table. A dump of the table alone is useless; the secret is 256 random bits, so even a
dump *with* the pepper leaves guessing impractical. Why not bcrypt or Argon2? Those defend low-entropy passwords
against offline guessing; a 256-bit random secret has nothing to guess, and the verify path runs on every request,
so a slow hash would only cost latency ([ADR 0003](../adr/0003-hashing-api-keys.md)).

## 3. Rotate without an outage

Two rotations, two mechanisms:

- **The key:** `POST /v1/keys/{id}/rotate` issues a new secret and keeps the old one valid for an overlap window,
  so clients can roll without a gap.
- **The pepper:** the secret holds every live version and which one is current. Keys hashed with an older version
  keep verifying; new keys use the new one. The experiment on both emulators confirmed it, and also that retiring a
  version too early turns its keys into "unknown" at once
  ([results](../../results/labs/pepper-rotation-floci.json)).

## Creation must be idempotent

A client that times out on `POST /v1/keys` and retries must not get two keys. keyedge stores an idempotency record
in the same DynamoDB transaction as the key. Fifty concurrent pairs of the same request produced exactly fifty keys
on both emulators ([results](../../results/labs/idempotency-race-floci.json),
[ADR 0006](../adr/0006-idempotent-key-creation.md)).

## Revocation is bounded, not instant

Sidecars cache verdicts. A revoked key keeps working at a sidecar until its cached verdict expires (60 s), and
during a control-plane outage up to that plus the stale window. That is written down as an accepted risk, not
discovered in an incident ([threat model](../threat-model.md)).
