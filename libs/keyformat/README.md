# keyformat

The keyedge API-key format. Pure Java (JDK + JSpecify only): time and randomness are injected.

```
ke_<env>_<keyId>_<secret>_<crc>                                   85 characters
  env    live | test                                              4
  keyId  Crockford base32, 128 bits: 48-bit ms timestamp + 80 random bits   26
  secret base64url, no padding, 32 random bytes (canonical form only)       43
  crc    base62, fixed width, CRC32 of "ke_<env>_<keyId>_<secret>"          6
```

- **Checksum:** the sidecar rejects typos and made-up strings without a network call; secret scanners
  match `ke_(live|test)_[0-7][0-9A-HJKMNP-TV-Z]{25}_[A-Za-z0-9_-]{43}_[0-9A-Za-z]{6}` and confirm the CRC
  before alerting.
- **keyId in the key:** the stored hash is found by id; the secret is never an index.
- **Storage:** only `HMAC-SHA256(secret, pepper)` + the pepper version (`SecretHash`). The pepper lives
  in Secrets Manager (Phase 005).
- **Parsing never throws** and checks the length first, so any untrusted input is cheap to reject.
  `MalformedReason`: `LENGTH`, `PREFIX`, `ENVIRONMENT`, `CHARSET`, `CHECKSUM`.

`./gradlew -q :libs:keyformat:run --args=demo` prints a test key, its display hint and its parse.
