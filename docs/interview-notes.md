# Interview notes

Short answers, each backed by something in the repo.

**"Why hash API keys instead of encrypting them?"** The service never needs the key back, only to check it. An
HMAC with a pepper kept in Secrets Manager means a database dump alone yields nothing usable; a versioned pepper
lets it rotate without breaking old keys ([ADR 0003](adr/0003-hashing-api-keys.md), runbook §8).

**"How fast is revocation?"** Bounded, not instant: 60 s at a sidecar (verdict TTL), up to TTL + 5 min while the
control plane is down under fail-static. That is a stated trade-off, measured in the outage experiment
([ADR 0010](adr/0010-fail-static-control-plane-policy.md)).

**"How do you know the rate limiter is correct?"** Unit tests with threads passed for a buggy bucket. jcstress ran
~200 million interleavings and showed the race one time in ten; the CAS versions had zero forbidden outcomes
([notebook](lab-notebook.md#naive-bucket-race-phase-003)).

**"Is Reactor faster than virtual threads?"** Same use case, same machine, open-model load, prediction written
first: the ceiling was our own bulkheads, and Reactor held 600 req/s where the virtual-thread adapters failed
earlier. What the numbers do not show: a quiet host, bigger bulkheads
([notebook](lab-notebook.md#vt-vs-reactor-phase-015)).

**"Why a native sidecar?"** Start-up 322 ms vs 3.9 s and about half the memory, which matters for a container next
to every task; the cost is build time and reflection metadata (we shipped a native image that silently dropped
every log line until the ECS run showed it) ([ADR 0012](adr/0012-native-images-and-supply-chain.md)).

**"How do you know emulator-based tests mean anything?"** The same suite on two independent emulators, every
difference documented; claims that need a real account (IAM, KMS policies, Fargate timings, ALB draining) are
labelled untested ([parity](parity.md)).

**"Why two CDK languages?"** To show IaC can be migrated safely: one spec, two apps, a diff that proves the
templates are byte-identical ([ADR 0017](adr/0017-two-cdk-languages-one-spec.md)).

**"What's in your CI supply chain?"** Actions pinned by SHA, nothing granted by default, no cloud credentials at
all, no caches restored in release runs, zizmor and actionlint, SBOMs, a Trivy gate, signatures.

**"Why not run CI on every push to main?"** Pull requests already ran it; tags run the release jobs; manual
dispatch covers the rest.

**"What would you do differently with a real account?"** Test IAM and key policies for real, measure draining and
start order on Fargate, size tasks from Fargate CPU numbers.
