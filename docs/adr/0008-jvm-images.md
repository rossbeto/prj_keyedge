# 8. JVM images: distroless, built from the Gradle distribution

Date: 2026-11-23 · Status: accepted

## Decision
One `deploy/Dockerfile.jvm` for every service: `gcr.io/distroless/java25-debian13:nonroot` pinned by digest, the
service's `installDist` libraries copied in, uid 65532, the main class from compose `command:`. Compose runs
every service read-only, `tmpfs: /tmp`, `cap_drop: ALL`, `no-new-privileges`, with a memory limit, published on
127.0.0.1 only. Health checks call `dev.keyedge.ops.HealthProbe` (JDK only: no shell or curl in the image).

## Why
86–99 MB images, no shell to exploit, nothing to patch but the JRE. Building on the host avoids downloading
Gradle and all dependencies inside every image build; CI builds the same way.

## Consequences
- Health indicators must not depend on the working directory (the AWS Emulator variant runs as the host UID to
  read its mode-600 files): the disk-space check measures `/tmp`.
- Native images (Phase 010) get their own Dockerfile.
