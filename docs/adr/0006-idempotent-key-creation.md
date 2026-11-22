# 6. Idempotent key creation: key and idempotency record in one transaction

Date: 2026-11-23 · Status: accepted

## Context
Creating a key returns its secret exactly once. A client that retries after a timeout must not get a second key,
and a crash between "key written" and "request marked done" must not allow one.

## Decision
- One DynamoDB item per key (`K#<keyId>`): `attribute_not_exists(PK)` guarantees unique ids, and verify is one
  `GetItem`.
- The idempotency record (`I#<tenant>#<key>`, request hash, `IN_FLIGHT`/`DONE`, TTL 24 h) is claimed with a
  conditional put, then **the key put and the record's `IN_FLIGHT → DONE` update are one `TransactWriteItems`**.
  Cancellation reason index 0 = id collision (retry with a new id), index 1 = the claim was taken over (409).
- Same key + different request hash → 422; in flight → 409; done → replay **without the secret** (it is never
  stored), with `Idempotent-Replayed: true`. A claim older than 30 s may be taken over, conditionally on its start time.

## Consequences
- Proven by the `idempotency-race` experiment on both emulators (50/50).
- A client that loses the first response cannot get the secret again: it must rotate the key. Documented in the API.
