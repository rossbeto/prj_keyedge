# 12. Native images and supply-chain basics

Date: 2026-11-23 · Status: accepted

## Context
The edge sidecar runs next to every service task: its start-up time, memory and CPU are paid once per task. The
lab also needs to show what is in its images and that they were not tampered with — locally, without a cloud
identity.

## Decision
- **Native images built in a container.** Micronaut's `dockerBuildNative` compiles inside the digest-pinned
  GraalVM builder image (`ghcr.io/graalvm/native-image-community:25-ol9`); the runtime is
  `gcr.io/distroless/cc-debian13:nonroot` (pinned), non-root, read-only rootfs in compose. A foojay GraalVM
  toolchain ships `native-image` as an empty file (B-04), so the host needs only Docker. `make native S=<svc>`,
  one image at a time (~4–5 min, ~5 GB). Convention plugin `keyedge.native`.
- **Reachability metadata**: the GraalVM metadata repository first; the tracing agent (`test -Pagent`, tests on
  the GraalVM JDK) for the rest, curated by `scripts/native_metadata.py` to the libraries that need it (for the
  edge: Caffeine's generated cache classes — 9 entries of 3 039 recorded). An ArchUnit rule forbids reflection
  outside the composition root.
- **Health checks without a JVM**: each `Application.main` answers `--health-check <url>` (`HealthProbe`), so the
  native binary probes itself; `deploy/compose.native.yaml` swaps image, command and health check
  (`make up FLAVOR=native`).
- **Preview APIs in native**: quotes' structured fan-out needs `--enable-preview` at native build time
  (`-Pkeyedge.native.preview=true`, set by `make native S=quotes`).
- **Supply chain** (`scripts/supply_chain.sh`, all tools digest-pinned containers, images passed as `docker save`
  archives — no Docker socket in third-party containers):
  - SBOM: CycloneDX of the runtime classpath (Gradle plugin) **and** of the image (syft). For a native image the
    image SBOM sees only OS packages — the Java libraries are compiled into the binary — so the classpath SBOM is
    the one that lists them.
  - Trivy: fail on CRITICAL, report HIGH; `.trivyignore` entries need a reason and an expiry.
  - cosign: a local key pair in `~/.keyedge/cosign` (0600, never committed), images pushed to a local registry
    (`localhost:15500`), signed and verified by digest without a transparency log. Keyless signing needs a real
    CI identity (USER-ACTIONS U-08).

## Consequences
- The native edge (measured, `results/labs/native-vs-jvm.json`): Micronaut ready in ~22 ms in-process vs ~1.1–1.6 s
  on the JVM; ~0.32 s from `docker start` to readiness vs ~3.9 s; RSS 88 vs 166 MiB idle and 131 vs 213 MiB after
  200 req/s; edge CPU during the 30 s load 12.8 s vs 26.5 s. The planned thresholds (< 100 ms to ready including
  the container start, < 1/3 of the RSS) were not met; the gains are still 10× start-up, ~2× memory and CPU.
- Every new library with reflection or generated classes needs an agent run before it works natively; the stack
  smoke test (`make up FLAVOR=native && make demo`) is the check.
- Native builds are slow; CI runs them on tags and manual dispatch only (`.github/workflows/native.yml`).
