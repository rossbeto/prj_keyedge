# keyedge developer commands (masterplan §11.1). `make help` lists them.
# The AWS target comes from .env (KEYEDGE_AWS_TARGET, default floci); override per command: make test-aws TARGET=aws-emulator
.DEFAULT_GOAL := help
SHELL := /bin/bash
GRADLE := ./gradlew
-include .env
TARGET ?= $(or $(KEYEDGE_AWS_TARGET),floci)
CDK_LANG ?= $(or $(KEYEDGE_CDK_LANG),java)
M ?=
B ?=
HADOLINT := hadolint/hadolint:v2.14.0-debian

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-20s\033[0m %s\n",$$1,$$2}'

## ---- Build and quality --------------------------------------------------------------
.PHONY: build lint fmt test test-all pit jcstress jmh terms gitleaks hadolint tools
build: ## Compile, test (fast) and assemble every module
	$(GRADLE) build

lint: terms hadolint ## Spotless, Checkstyle, Error Prone/NullAway (compile), ArchUnit, forbidden terms, hadolint
	$(GRADLE) spotlessCheck checkstyleMain checkstyleTest compileTestJava

fmt: ## Apply formatting
	$(GRADLE) spotlessApply

test: ## Fast tests only (no containers), JaCoCo reports
	$(GRADLE) test

test-all: ## Every test level (needs Docker and emulators)
	$(GRADLE) test testAws testIt testE2e

pit: ## Mutation tests: make pit M=libs:keyformat
	$(GRADLE) :$(M):pitest

jcstress: ## jcstress: make jcstress M=libs:ratelimit [MODE=quick]
	$(GRADLE) :$(M):jcstress -Pjcstress.mode=$(or $(MODE),quick)

jmh: ## JMH benchmarks: make jmh M=libs:ratelimit [B=<regex>] (never run other builds meanwhile)
	$(GRADLE) :$(M):jmh $(if $(B),-Pjmh.includes=$(B))

terms: ## Forbidden-terms scan (working tree + commit messages)
	python3 scripts/forbidden_terms.py check --history

gitleaks: ## Secret scan of history and working tree
	scripts/gitleaks.sh

hadolint: ## Lint Dockerfiles
	@find . -name Dockerfile -not -path './build/*' -not -path '*/node_modules/*' -print0 | xargs -0 -r -n1 sh -c 'docker run --rm -i $(HADOLINT) hadolint - < "$$0"'

tools: ## Download pinned sops/age into .tools/
	scripts/tools.sh

## ---- Emulators (KEYEDGE_AWS_TARGET / TARGET=floci|aws-emulator) ----------------------
.PHONY: emu-up emu-down emu-health emu-env emu-image emu-smoke emu-reset test-aws emu-parity emu-image-if-missing
emu-up: ## Start the emulator for TARGET and run the real-AWS guard
	scripts/emu.sh up $(TARGET)

emu-down: ## Stop both emulators
	scripts/emu.sh down $(TARGET)

emu-health: ## Health check for TARGET
	scripts/emu.sh health $(TARGET)

emu-env: ## Print how to load the TARGET environment into a subshell
	@echo '( . <(scripts/aws-env.sh $(TARGET)) && aws sts get-caller-identity )'

emu-image: ## Rebuild the AWS Emulator image from the LATEST package (never pinned)
	docker build --pull --no-cache -t keyedge/aws-emulator deploy/aws-emulator

emu-reset: ## Clean state: restart the TARGET emulator (AWS Emulator has no reset API; restart = empty)
	scripts/emu.sh down $(TARGET) && scripts/emu.sh up $(TARGET)

test-aws: ## AWS contract suite on TARGET (both emulators when TARGET=all); Testcontainers, needs `make emu-image` once
	$(GRADLE) testAws $(if $(filter all,$(TARGET)),,--tests '*$(if $(filter aws-emulator,$(TARGET)),AwsEmulator,Floci)ContractTest*')

emu-parity: emu-image-if-missing ## Contract suite on both emulators, then results/parity/<run>.json and docs/parity.md
	$(GRADLE) :libs:awstarget:testAws --rerun-tasks
	python3 scripts/parity_report.py

emu-image-if-missing:
	@docker image inspect keyedge/aws-emulator >/dev/null 2>&1 || docker build --pull -t keyedge/aws-emulator deploy/aws-emulator

emu-smoke: ## Observed smoke test of every operation the design needs, on TARGET
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 --with pyyaml scripts/emulator-smoke/smoke.py $(TARGET) )

## ---- Lab notebook (experiments: hypothesis -> measurement -> verdict) --------------------
.PHONY: lab
RUN_ID := $(shell date +%Y-%m-%d)-$(shell git rev-parse --short HEAD 2>/dev/null)
lab: ## Run one experiment: make lab E=naive-bucket-race|limiter-cost|single-flight|refresh-ahead|vt-handoff|control-plane-outage|breaker-vs-none|adaptive-vs-fixed|fanout-models|cancel-on-failure|retry-only-idempotent|token-stampede|native-vs-jvm|pinning|scoped-vs-threadlocal
	@case "$(E)" in \
	  naive-bucket-race) \
	    $(GRADLE) :libs:ratelimit:jcstress -Pjcstress.mode=$(or $(MODE),quick) -Pjcstress.tests='.*Bucket.*|.*Gcra.*|.*Permit.*' > build/jcstress-naive-bucket-race.log 2>&1 || true; \
	    python3 lab/jcstress_verdict.py build/jcstress-naive-bucket-race.log results/labs/naive-bucket-race.json ;; \
	  limiter-cost) \
	    $(GRADLE) :libs:ratelimit:jmh && \
	    python3 lab/jmh_verdict.py libs/ratelimit/build/results/jmh/results.json results/jmh/$(RUN_ID) && \
	    cp results/jmh/$(RUN_ID)/limiter-cost.json results/labs/limiter-cost.json ;; \
	  single-flight) $(GRADLE) :services:edge:testExperiment --rerun-tasks --tests '*EdgeExperiments.singleFlight' ;; \
	  refresh-ahead) $(GRADLE) :services:edge:testExperiment --rerun-tasks --tests '*EdgeExperiments.refreshAhead' ;; \
	  vt-handoff) $(GRADLE) :services:edge:testExperiment --rerun-tasks --tests '*EdgeExperiments.vtHandoff' ;; \
	  control-plane-outage) $(GRADLE) :services:edge:testExperiment --rerun-tasks --tests '*ResilienceExperiments.controlPlaneOutage' ;; \
	  breaker-vs-none) $(GRADLE) :services:edge:testExperiment --rerun-tasks --tests '*ResilienceExperiments.breakerVsNone' ;; \
	  adaptive-vs-fixed) $(GRADLE) :services:edge:testExperiment --rerun-tasks --tests '*AdaptiveLimitExperiments.adaptiveVsFixed' ;; \
	  fanout-models) $(GRADLE) :services:quotes:testExperiment --rerun-tasks --tests '*FanoutExperiments.fanoutModels' ;; \
	  cancel-on-failure) $(GRADLE) :services:quotes:testExperiment --rerun-tasks --tests '*FanoutExperiments.cancelOnFailure' ;; \
	  retry-only-idempotent) $(GRADLE) :services:quotes:testExperiment --rerun-tasks --tests '*UpstreamPolicyExperiments.retryOnlyIdempotent' ;; \
	  token-stampede) $(GRADLE) :services:quotes:testExperiment --rerun-tasks --tests '*UpstreamPolicyExperiments.tokenStampede' ;; \
	  native-vs-jvm) TARGET=$(TARGET) python3 lab/native_vs_jvm.py ;; \
	  pinning) $(GRADLE) :lab:pinning:pinning && python3 lab/pinning_verdict.py ;; \
	  scoped-vs-threadlocal) $(GRADLE) :libs:observe:testExperiment --rerun-tasks ;; \
	  *) echo "unknown experiment '$(E)'"; exit 2 ;; \
	esac

## ---- Services ---------------------------------------------------------------------------
.PHONY: seed run contracts-check
contracts-check: ## OpenAPI documents generated at compile time must match contracts/openapi/*.yml
	$(GRADLE) :services:keys:test --tests '*OpenApiSnapshotTest*'

seed: ## Table, secrets and SSM contract on the TARGET emulator: the CDK stacks (Phase 012; was scripts/seed.py)
	scripts/emu.sh assert-local $(TARGET)
	$(MAKE) --no-print-directory cdk-deploy TARGET=$(TARGET)

run: ## Run one service on the host JVM against TARGET: make run S=keys|issuer
	( . <(scripts/aws-env.sh $(TARGET)) && KEYEDGE_AWS_TARGET=$(TARGET) MICRONAUT_ENVIRONMENTS=local $(GRADLE) --no-configuration-cache :services:$(S):run )

## ---- Infrastructure as code (Phase 012: CDK Java; Phase 013 adds CDK_LANG=python) -------------
.PHONY: cdk-synth cdk-test cdk-test-all cdk-nag cdk-versions-check cdk-parity cdk-deploy cdk-destroy cdk-deploy-parity cdk-stub-demo
STACKS ?=
cdk-synth: ## Synthesise both modes of the CDK_LANG app (java|python) into infra/out/<lang>/<mode>; nag errors fail
	scripts/cdk-synth.sh $(CDK_LANG) local
	scripts/cdk-synth.sh $(CDK_LANG) prodlike

cdk-test: ## Tests of the CDK_LANG app (java: assertions+snapshots; python: pytest, ruff, mypy, import-linter)
	@if [ "$(CDK_LANG)" = python ]; then \
	  cd infra/python && uv run --frozen pytest -q && uv run --frozen ruff check . && uv run --frozen ruff format --check . \
	    && uv run --frozen mypy keyedge_infra && uv run --frozen lint-imports; \
	else $(GRADLE) :infra:java:test -Pcdk; fi

cdk-test-all: ## Both CDK apps' tests and the template diff tool's tests
	$(MAKE) --no-print-directory cdk-test CDK_LANG=java
	$(MAKE) --no-print-directory cdk-test CDK_LANG=python
	uv run --project infra/python --frozen pytest -q scripts/tests/test_cdk_template_diff.py

cdk-nag: cdk-synth ## The cdk-nag gate: synth both modes of CDK_LANG; any unacknowledged finding fails

cdk-versions-check: ## aws-cdk-lib, cdk-nag, constructs: Gradle catalog == uv.lock; cdk.json context blocks equal
	python3 scripts/cdk_versions_check.py

cdk-parity: cdk-versions-check ## Synth both apps x both modes; templates must be identical (results/parity/cdk-templates.json)
	@for l in java python; do for m in local prodlike; do scripts/cdk-synth.sh $$l $$m || exit 1; done; done
	rm -f results/parity/cdk-templates.json
	python3 scripts/cdk_template_diff.py infra/out/java/local infra/out/python/local --mode local --report results/parity/cdk-templates.json
	python3 scripts/cdk_template_diff.py infra/out/java/prodlike infra/out/python/prodlike --mode prodlike --report results/parity/cdk-templates.json

cdk-deploy: ## Deploy local templates to the TARGET emulator and verify each resource: make cdk-deploy [TARGET=] [STACKS="..."]
	@[ -f infra/out/$(CDK_LANG)/local/manifest.json ] || $(MAKE) --no-print-directory cdk-synth
	CDK_LANG=$(CDK_LANG) scripts/cdk-deploy.sh deploy $(TARGET) $(STACKS)

cdk-destroy: ## Delete the deployed stacks from the TARGET emulator (reverse order)
	CDK_LANG=$(CDK_LANG) scripts/cdk-deploy.sh destroy $(TARGET) $(STACKS)

cdk-stub-demo: ## The stub trap on Floci: stubbing off fails loudly; on, CloudFormation lies and cdk-verify catches it
	scripts/cdk-stub-demo.sh

cdk-deploy-parity: ## Try every local stack on TARGET; results/parity/cdk-<target>.json (gaps are parity rows)
	CDK_LANG=$(CDK_LANG) scripts/cdk-deploy.sh parity $(TARGET)

## ---- Bench and lab notebook (Phase 015) ---------------------------------------------------------
# CPU pinning on this 14-CPU host (the fingerprint records nproc and the load average)
BENCH_ENV := BENCH_SUT_CPUS=$(or $(BENCH_SUT_CPUS),2-5) BENCH_SUPPORT_CPUS=$(or $(BENCH_SUPPORT_CPUS),6-9) BENCH_K6_CPUS=$(or $(BENCH_K6_CPUS),10-13)
BENCH = JAVA_HOME=$$(cat bench/build/java-home) bench/build/install/bench/bin/bench
# in-process experiments (no stack); idempotency-race/pepper-rotation run in `make test-aws`, native-vs-jvm needs the stack
LAB_ALL ?= naive-bucket-race single-flight refresh-ahead vt-handoff control-plane-outage breaker-vs-none adaptive-vs-fixed fanout-models cancel-on-failure retry-only-idempotent token-stampede pinning scoped-vs-threadlocal limiter-cost
.PHONY: bench bench-smoke bench-install report docs-check lab-all
bench-install:
	@$(GRADLE) -q :bench:installDist

bench: bench-install ## Prediction first, then the run: make bench S=vt-vs-reactor|native-overhead|fargate-size (stack up, observe OFF, no builds)
	@[ -n "$(S)" ] || { echo "S=<scenario> (bench/scenarios/*.yaml)"; exit 2; }
	@if docker ps --format '{{.Names}}' | grep -qx keyedge-prometheus-1; then echo "REFUSED: the observe profile is running (make stop): it skews results"; exit 1; fi
	@run=$$(date -u +%Y%m%d-%H%M%S)-$(S); \
	  $(CONTAINER_ENV) $(BENCH_ENV) $(BENCH) predict --scenario $(S) --run $$run && \
	  $(CONTAINER_ENV) $(BENCH_ENV) $(BENCH) run --scenario $(S) --run $$run && \
	  $(BENCH) validate

bench-smoke: ## The harness: 10 s at 20 rps through the edge -> a valid results/bench/<run>/bench-result.json
	@$(MAKE) --no-print-directory bench S=bench-smoke

report: bench-install ## Regenerate the notebook index, bench sections, README block and docs/media charts from results/
	$(BENCH) report

docs-check: bench-install ## Fail if a generated block or chart is stale, or a notebook verdict disagrees with its results file
	$(BENCH) report --check

lab-all: ## Re-run every in-process experiment (results/labs/*.json), then `make report`
	@for e in $(LAB_ALL); do echo "== $$e"; $(MAKE) --no-print-directory lab E=$$e || echo "FAILED: $$e"; done
	@$(MAKE) --no-print-directory report

## ---- Emulated ECS (Phase 014): CDK templates on Floci with real task containers --------------------
.PHONY: ecs-up ecs-deploy ecs-status ecs-smoke ecs-experiments ecs-down
ecs-up: ## Floci-ECS (Docker socket: opt-in, root-equivalent), data/config stacks, support services
	CDK_LANG=$(CDK_LANG) scripts/ecs.sh up

ecs-deploy: ## Cluster, ALB, quotes service from the CDK templates; waits for a running task
	CDK_LANG=$(CDK_LANG) scripts/ecs.sh deploy

ecs-status: ## Service counts, task containers and their addresses
	scripts/ecs.sh status

ecs-smoke: ## A signed quote request through the edge of the running ECS task
	scripts/ecs.sh smoke

ecs-experiments: ## task-start-order and drain-on-deploy (through the ALB on 127.0.0.1:15180); results/ecs/*.json
	scripts/ecs.sh start-order
	scripts/ecs.sh drain

ecs-down: ## Delete the ECS stacks, stop the support services and Floci-ECS; counts leftover task containers
	CDK_LANG=$(CDK_LANG) scripts/ecs.sh down

## ---- Secrets: SOPS with age + the emulator's KMS key (D-44) ---------------------------------------
.PHONY: age-key secrets-encrypt secrets-rekey secrets-decrypt-check secrets-sync
age-key: ## Create ~/.config/keyedge/age.key once (outside the repo)
	scripts/secrets.sh age-key
secrets-encrypt: ## New secrets/<TARGET>/local.enc.yaml with fresh random values (age + KMS recipients)
	scripts/secrets.sh encrypt $(TARGET)
secrets-rekey: ## Point the KMS recipient at the current KmsStack key (after an emulator restart)
	scripts/secrets.sh rekey $(TARGET)
secrets-decrypt-check: ## Decrypt into a pipe and validate (VIA=age|kms); never to disk
	scripts/secrets.sh decrypt-check $(TARGET) $(or $(VIA),age)
secrets-sync: ## Put the SOPS documents into Secrets Manager on TARGET (overrides CDK-generated values)
	scripts/secrets.sh sync $(TARGET)

## ---- Images and the compose stack ---------------------------------------------------------
SERVICES := issuer keys upstream-sim edge quotes
COMPOSE := docker compose -f deploy/compose.yaml
FLAVOR ?= jvm
STACK := $(COMPOSE)$(if $(filter native,$(FLAVOR)), -f deploy/compose.native.yaml)
ifeq ($(TARGET),aws-emulator)
CONTAINER_ENV := KEYEDGE_AWS_TARGET=aws-emulator KEYEDGE_CONTAINER_AWS_ENDPOINT=https://localhost:15297 KEYEDGE_CONTAINER_CONNECT_HOST=aws-emulator KEYEDGE_UID=$(shell id -u) KEYEDGE_GID=$(shell id -g)
else
CONTAINER_ENV := KEYEDGE_AWS_TARGET=floci KEYEDGE_CONTAINER_AWS_ENDPOINT=http://floci:4566
endif
.PHONY: images image-size up stop logs ps chaos chaos-reset container-env jfr
container-env:
	@for kv in $(CONTAINER_ENV); do echo "export $$kv"; done

D ?= 60s
jfr: ## Flight recording of one JVM service under demo traffic: make jfr S=edge|quotes|keys D=60s -> results/jfr/
	scripts/jfr.sh $(S) $(D) $(TARGET)

images: ## Build the JVM images (Gradle distribution on the host, then deploy/Dockerfile.jvm)
	$(GRADLE) $(foreach s,$(SERVICES),:services:$(s):installDist)
	$(foreach s,$(SERVICES),docker build -q -f deploy/Dockerfile.jvm --build-arg SERVICE=$(s) -t keyedge/$(s):dev . &&) true

image-size: ## Fail if an image exceeds its budget (MB; native budgets apply when the image exists)
	@scripts/image_size.sh keyedge/issuer:dev 110 keyedge/keys:dev 125 keyedge/upstream-sim:dev 110 keyedge/edge:dev 125 keyedge/quotes:dev 125
	@scripts/image_size.sh --if-present keyedge/edge:native 59 keyedge/quotes:native 56 keyedge/keys:native 60

## ---- Native images and supply chain (Phase 010) -------------------------------------------
.PHONY: native sbom scan sign verify supply-check release-check changelog workflows-check docs-policy
NATIVE_PREVIEW := $(if $(filter quotes,$(S)),-Pkeyedge.native.preview=true)
native: ## Native image in the GraalVM builder container: make native S=edge|quotes|keys (one at a time, ~5 min)
	$(GRADLE) --no-configuration-cache :services:$(S):dockerBuildNative $(NATIVE_PREVIEW)

SUPPLY_IMAGES ?= keyedge/edge:dev keyedge/edge:native keyedge/quotes:native keyedge/keys:native
sbom: ## CycloneDX: classpath SBOMs (Gradle) + image SBOMs (syft) for SUPPLY_IMAGES
	$(GRADLE) $(foreach s,edge quotes keys,:services:$(s):cyclonedxDirectBom)
	scripts/supply_chain.sh sbom $(SUPPLY_IMAGES)

scan: ## Trivy on SUPPLY_IMAGES: fail on CRITICAL, report HIGH (.trivyignore holds reasons)
	scripts/supply_chain.sh scan $(SUPPLY_IMAGES)

sign: ## cosign sign SUPPLY_IMAGES by digest in the local registry 127.0.0.1:15500 (local key in ~/.keyedge/cosign)
	scripts/supply_chain.sh sign $(SUPPLY_IMAGES)

verify: ## cosign verify SUPPLY_IMAGES
	scripts/supply_chain.sh verify $(SUPPLY_IMAGES)

supply-check: image-size scan sign verify ## Budgets, scan, sign, verify (local registry + local cosign key)

release-check: ## Pre-tag checklist, PASS/FAIL per check: make release-check [TAG=v1.0.0] [RELEASE_ALLOW_DIRTY=1]
	@RELEASE_ALLOW_DIRTY=$(or $(RELEASE_ALLOW_DIRTY),0) scripts/release_check.sh $(TAG)

changelog: ## The CHANGELOG.md section of a tag (release notes): make changelog TAG=v1.0.0
	@v=$(TAG); v=$${v#v}; awk -v v="$$v" '$$0 ~ "^## \\[?"v"\\]?" {p=1; next} /^## / {p=0} p' CHANGELOG.md

ACTIONLINT := rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
workflows-check: ## Workflow policy (no branch push, SHA pins, least privilege) + actionlint 1.7.12 + zizmor
	uv run -q --with pytest --with pyyaml pytest -q scripts/tests/test_workflow_policy.py
	docker run --rm -v "$$PWD:/repo:ro" -w /repo $(ACTIONLINT) -color
	uvx -q zizmor==1.33.1 --offline .github/workflows

docs-policy: ## Portfolio card contract, relative links, README numbers linked to evidence; release_check self-test
	uv run -q --with pytest pytest -q scripts/tests/test_docs_policy.py
	scripts/tests/test_release_check.sh

up: ## Emulator + seed + services (TARGET=floci|aws-emulator, FLAVOR=jvm|native); ports 127.0.0.1:15xxx
	scripts/emu.sh up $(TARGET)
	$(MAKE) --no-print-directory seed TARGET=$(TARGET)
	$(CONTAINER_ENV) $(STACK) up -d --wait --force-recreate issuer keys upstream-sim toxiproxy edge quotes

stop: ## Stop the services and the observe profile (emulators keep running: make emu-down)
	$(STACK) -f deploy/compose.observe.yaml stop prometheus grafana jaeger quotes edge issuer keys upstream-sim toxiproxy

logs: ## Follow logs: make logs S=keys
	$(COMPOSE) logs -f $(S)

ps: ## Containers of the stack
	$(COMPOSE) ps

chaos: ## Simulator scenario (S=brownout-pricing...) or control-plane chaos via Toxiproxy (S=keys-down|keys-slow|keys-flaky)
	@case "$(S)" in \
	  keys-*) scripts/toxics.sh scenario $(S) ;; \
	  *) curl -sf -X PUT "http://127.0.0.1:15201/__chaos?name=$(S)" && echo ;; \
	esac

chaos-reset: ## Healthy scenario and zeroed counters; Toxiproxy toxics cleared by scripts/toxics.sh
	curl -sf -X PUT "http://127.0.0.1:15201/__chaos?name=healthy" > /dev/null && curl -sf -X POST http://127.0.0.1:15201/__reset && echo
	scripts/toxics.sh clear

.PHONY: observe dashboards
observe: ## Traces (Jaeger 15686), metrics (Prometheus 15090, Grafana 15030): services recreated with OTLP export on
	$(CONTAINER_ENV) $(STACK) -f deploy/compose.observe.yaml up -d --wait --force-recreate issuer keys upstream-sim edge quotes prometheus jaeger grafana

dashboards: ## Regenerate the Grafana dashboards (deploy/observe/grafana/dashboards) from scripts/build_dashboards.py
	python3 scripts/build_dashboards.py

.PHONY: demo calibrate resilience-check quotes-check
quotes-check: ## Quotes under simulator scenarios: blocking == Reactor twin, fx-down -> FX_UNAVAILABLE warning
	uv run -q python scripts/quotes_check.py

resilience-check: ## Control plane down under the running stack: warm key served stale, cold key 503 (~70 s)
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 python scripts/resilience_check.py )

demo: ## Walk through the running stack: token, create, replay, signed verify, usage, revoke
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 python scripts/demo.py )

calibrate: ## k6 open-model load on upstream-sim alone: can the bench's downstream keep up? (Phase 006)
	scripts/calibrate.sh
