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
lab: ## Run one experiment: make lab E=naive-bucket-race|limiter-cost|single-flight|refresh-ahead|vt-handoff|control-plane-outage|breaker-vs-none|adaptive-vs-fixed|fanout-models|cancel-on-failure|retry-only-idempotent|token-stampede|native-vs-jvm
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
	  *) echo "unknown experiment '$(E)'"; exit 2 ;; \
	esac

## ---- Services ---------------------------------------------------------------------------
.PHONY: seed run contracts-check
contracts-check: ## OpenAPI documents generated at compile time must match contracts/openapi/*.yml
	$(GRADLE) :services:keys:test --tests '*OpenApiSnapshotTest*'

seed: ## Create table + secrets on the TARGET emulator (until CDK does it, Phase 012)
	scripts/emu.sh assert-local $(TARGET)
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 python scripts/seed.py )

run: ## Run one service on the host JVM against TARGET: make run S=keys|issuer
	( . <(scripts/aws-env.sh $(TARGET)) && KEYEDGE_AWS_TARGET=$(TARGET) MICRONAUT_ENVIRONMENTS=local $(GRADLE) --no-configuration-cache :services:$(S):run )

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
.PHONY: images image-size up stop logs ps chaos chaos-reset
images: ## Build the JVM images (Gradle distribution on the host, then deploy/Dockerfile.jvm)
	$(GRADLE) $(foreach s,$(SERVICES),:services:$(s):installDist)
	$(foreach s,$(SERVICES),docker build -q -f deploy/Dockerfile.jvm --build-arg SERVICE=$(s) -t keyedge/$(s):dev . &&) true

image-size: ## Fail if an image exceeds its budget (MB; native budgets apply when the image exists)
	@scripts/image_size.sh keyedge/issuer:dev 110 keyedge/keys:dev 125 keyedge/upstream-sim:dev 110 keyedge/edge:dev 125 keyedge/quotes:dev 125
	@scripts/image_size.sh --if-present keyedge/edge:native 47 keyedge/quotes:native 46 keyedge/keys:native 49

## ---- Native images and supply chain (Phase 010) -------------------------------------------
.PHONY: native sbom scan sign verify release-check
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

release-check: image-size scan sign verify ## Budgets, scan, sign, verify

up: ## Emulator + seed + services (TARGET=floci|aws-emulator, FLAVOR=jvm|native); ports 127.0.0.1:15xxx
	scripts/emu.sh up $(TARGET)
	$(MAKE) --no-print-directory seed TARGET=$(TARGET)
	$(CONTAINER_ENV) $(STACK) up -d --wait --force-recreate issuer keys upstream-sim toxiproxy edge quotes

stop: ## Stop the services (emulators keep running: make emu-down)
	$(STACK) stop quotes edge issuer keys upstream-sim toxiproxy

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

.PHONY: demo calibrate resilience-check quotes-check
quotes-check: ## Quotes under simulator scenarios: blocking == Reactor twin, fx-down -> FX_UNAVAILABLE warning
	uv run -q python scripts/quotes_check.py

resilience-check: ## Control plane down under the running stack: warm key served stale, cold key 503 (~70 s)
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 python scripts/resilience_check.py )

demo: ## Walk through the running stack: token, create, replay, signed verify, usage, revoke
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 python scripts/demo.py )

calibrate: ## k6 open-model load on upstream-sim alone: can the bench's downstream keep up? (Phase 006)
	scripts/calibrate.sh
