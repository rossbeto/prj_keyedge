# Threat model (STRIDE)

Scope: the lab as built, run locally on an emulator, and the infrastructure as synthesised for `prodlike`. Each row
names the control in the code and what is left (residual risk). Accepted risks are stated, not hidden.

| # | Asset / flow | Threat (STRIDE) | Control | Residual risk |
|---|---|---|---|---|
| 1 | Key issuance (`POST /v1/keys`) | **S** forged admin; **R** "I never created that key" | OAuth token from the issuer (scope `keys:write`), tenant from the token, not the body; every create is idempotent and recorded with its request hash ([ADR 0006](adr/0006-idempotent-key-creation.md)) | the local issuer is a lab stand-in for a real IdP |
| 2 | Stored keys | **I** database dump reveals usable keys | only HMAC-SHA256(secret, pepper) is stored; the pepper lives in Secrets Manager, versioned, never next to the hash ([ADR 0003](adr/0003-hashing-api-keys.md)) | a dump *plus* the pepper allows offline guessing of keys; secrets are 256 random bits, so guessing stays impractical |
| 3 | Key in transit / logs | **I** key in a log line or trace | the JSON log encoder redacts bearer tokens, key-shaped strings and basic auth, stack traces included (`JsonLineEncoder` tests); the demo never prints a key | a new log field that carries a key in another shape; covered by the redaction tests only for known shapes |
| 4 | Verify path (edge → keys) | **S** a client calls `/internal/verify` itself; **T** replayed verify answers | internal channel signed with a caller secret and a nonce, separate from the public API ([ADR 0007](adr/0007-internal-verify-channel.md)) | the caller secret is shared by all sidecars of a service |
| 5 | Sidecar → app hop | **S** a request reaches the app without the edge ("it came from localhost") | the edge signs a principal header (HMAC, method, path, timestamp); the app verifies it; inbound `X-Keyedge-*` headers are stripped (D-26) | a compromised edge container can sign anything for its own task |
| 6 | Revocation | **E** a revoked key keeps working | verdict cache TTL 60 s; fail-static window 5 min only while keys is down ([ADR 0009](adr/0009-edge-admission.md), [ADR 0010](adr/0010-fail-static-control-plane-policy.md)) | **accepted:** revocation latency = TTL (60 s), up to TTL + stale window during a control-plane outage |
| 7 | Rate limits | **D** one key or tenant exhausts the app | per-key GCRA at each sidecar, adaptive concurrency limit to the app, bulkheads per upstream | **accepted:** limits are per task, not global; N tasks admit N × the per-key rate |
| 8 | Usage metering | **R/T** usage lost or inflated | usage aggregated per key at the edge, flushed in batches as atomic DynamoDB `ADD` counters; last flush on drain | a crash between flushes loses at most one batch window; a retried flush can count twice (counters are not idempotent): metering, not billing |
| 9 | Secrets flow | **I** plaintext secrets in the repo or on disk | SOPS documents encrypted to an age key (outside the repo) and the KMS key; decryption into pipes only; gitleaks on every commit ([ADR 0016](adr/0016-sops-with-age-and-kms.md)) | the age key on the developer machine is a single point of compromise for local secrets |
| 10 | Local emulator socket (`ecs` profile) | **E** a container with `/var/run/docker.sock` is root on the host | Floci-ECS and the AWS Emulator socket override are opt-in only, print a warning, bind 127.0.0.1, never run in CI ([ADR 0018](adr/0018-sidecar-finds-the-app-through-task-metadata.md)) | while it runs, anything that can reach Floci's API can start containers on the host |
| 11 | Real AWS by accident | **T/I** a lab command touches a real account | `aws` target refused without `KEYEDGE_ALLOW_REAL_AWS=yes`; `emu.sh assert-local` requires account 000000000000 before any deploy ([ADR 0002](adr/0002-two-local-aws-emulators.md)) | the guard itself is verified by a user action (USER-ACTIONS U-10) |
| 12 | CI supply chain | **T** a compromised action or workflow injection | actions pinned by SHA, `permissions: {}` by default, no cloud credentials at all, no caches in tag runs, zizmor + actionlint + dependency review (`make workflows-check`) | a pinned action with a vulnerability until Renovate bumps it |
| 13 | Images | **T** a vulnerable or swapped image | distroless/minimal bases, SBOMs (CycloneDX + syft), Trivy gate on CRITICAL, cosign signatures verified locally ([ADR 0012](adr/0012-native-images-and-supply-chain.md)) | keyless signing with a CI identity is a user action after the first push (U-08) |
| 14 | Emulator fidelity | **all** a control the emulator does not enforce looks tested | IAM, KMS key policies and resource policies are checked for shape only (cdk-nag), never claimed as tested ([parity.md](parity.md) P-10) | **accepted for the lab:** needs a real account to test |

## Out of scope

Multi-region, a real identity provider, DDoS beyond per-task limits, insider access to the developer machine.
