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
.PHONY: emu-up emu-down emu-health emu-env emu-image emu-smoke
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

emu-smoke: ## Observed smoke test of every operation the design needs, on TARGET
	( . <(scripts/aws-env.sh $(TARGET)) && uv run -q --with boto3 --with pyyaml scripts/emulator-smoke/smoke.py $(TARGET) )

## ---- Lab notebook (experiments: hypothesis -> measurement -> verdict) --------------------
.PHONY: lab
RUN_ID := $(shell date +%Y-%m-%d)-$(shell git rev-parse --short HEAD 2>/dev/null)
lab: ## Run one experiment: make lab E=naive-bucket-race|limiter-cost
	@case "$(E)" in \
	  naive-bucket-race) \
	    $(GRADLE) :libs:ratelimit:jcstress -Pjcstress.mode=$(or $(MODE),quick) -Pjcstress.tests='.*Bucket.*|.*Gcra.*|.*Permit.*' > build/jcstress-naive-bucket-race.log 2>&1 || true; \
	    python3 lab/jcstress_verdict.py build/jcstress-naive-bucket-race.log results/labs/naive-bucket-race.json ;; \
	  limiter-cost) \
	    $(GRADLE) :libs:ratelimit:jmh && \
	    python3 lab/jmh_verdict.py libs/ratelimit/build/results/jmh/results.json results/jmh/$(RUN_ID) && \
	    cp results/jmh/$(RUN_ID)/limiter-cost.json results/labs/limiter-cost.json ;; \
	  *) echo "unknown experiment '$(E)' (naive-bucket-race | limiter-cost)"; exit 2 ;; \
	esac
