# 3. Hashing API keys: HMAC-SHA256 with a versioned pepper

Date: 2026-11-23 · Status: accepted

## Context
The reference systems stored API keys in plaintext (the key was even an index). Keys must be verified on
every request through the sidecar's cache misses, so verification cost matters.

## Options
1. bcrypt / argon2 (slow, salted password hashes).
2. SHA-256 of the secret.
3. HMAC-SHA256 of the secret with a server-side pepper, the pepper version stored with each hash.

## Decision
Option 3. Secrets are 256 random bits, so brute force is not the threat a slow hash defends against;
a leaked table is. The pepper lives in Secrets Manager, so the table alone is useless. HMAC costs
microseconds. Each hash records its pepper version: new keys use the current pepper, old keys verify
with theirs, and an old pepper is retired when no key uses it (secrets are never stored, so nothing can
be re-hashed).

## Consequences
- Comparison is constant time (`MessageDigest.isEqual`, and a non-short-circuit `&` with the version check).
- Losing every pepper version invalidates every key: peppers are backed up with the secrets they protect.
