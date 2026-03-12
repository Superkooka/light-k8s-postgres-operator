VERSION   := $(shell grep '^version' build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
IMAGE     := k8s-postgres-operator
CLUSTER   := dev
CONTEXT   := k3d-$(CLUSTER)
NAMESPACE := default
KUBECTL   := kubectl --context=$(CONTEXT)
HELM      := helm --kube-context=$(CONTEXT)

.DEFAULT_GOAL := run

.PHONY: check-dependencies
check-dependencies:
	@echo "Checking dependencies..."
	@which docker  > /dev/null 2>&1 || (echo "docker missing.  https://docs.docker.com/get-docker/" && exit 1)
	@which kubectl > /dev/null 2>&1 || (echo "kubectl missing. https://kubernetes.io/docs/tasks/tools/" && exit 1)
	@which helm    > /dev/null 2>&1 || (echo "helm missing.    https://helm.sh/docs/intro/install/" && exit 1)
	@which k3d     > /dev/null 2>&1 || (echo "k3d missing.     https://k3d.io/#installation" && exit 1)
	@which java    > /dev/null 2>&1 || (echo "java missing.    https://adoptium.net/" && exit 1)
	@java -version 2>&1 | grep -q "21\|22\|23\|24\|25" || echo "warning: java 21+ recommended"
	@echo "Dependencies OK"

.PHONY: run
run: check-dependencies cluster-up build sync deploy logs

.PHONY: stop
stop: cluster-down

.PHONY: build
build:
	@echo "Building..."
	@./gradlew build -q
	@echo "Build OK"

.PHONY: test
test:
	./gradlew cleanTest test

.PHONY: image
image:
	@echo "Building Docker image $(IMAGE):$(VERSION)..."
	@./gradlew jibDockerBuild -q
	@echo "Image OK"

.PHONY: sync
sync:
	@echo "Syncing CRDs and Helm chart version..."
	@./gradlew syncHelmVersion -q
	@echo "Sync OK"

.PHONY: cluster-up
cluster-up:
	@if k3d cluster list 2>/dev/null | grep -q "^$(CLUSTER)"; then \
		echo "Cluster '$(CLUSTER)' already exists, skipping."; \
	else \
		echo "Creating k3d cluster '$(CLUSTER)'..."; \
		k3d cluster create $(CLUSTER) --agents 1; \
		echo "Cluster OK"; \
	fi

.PHONY: cluster-down
cluster-down:
	@echo "Deleting cluster '$(CLUSTER)'..."
	@k3d cluster delete $(CLUSTER) 2>/dev/null || true

.PHONY: _check-context
_check-context:
	@CURRENT=$$(kubectl config current-context); \
	if [ "$$CURRENT" != "$(CONTEXT)" ]; then \
		echo ""; \
		echo "ERROR: current context is '$$CURRENT'"; \
		echo "ERROR: expected '$(CONTEXT)'"; \
		echo "ERROR: aborting to prevent accidental production deployment."; \
		echo ""; \
		exit 1; \
	fi

.PHONY: deploy
deploy: image _check-context
	@echo "Importing image into k3d..."
	@k3d image import $(IMAGE):$(VERSION) --cluster $(CLUSTER)
	@echo "Deploying via Helm..."
	@EXTRA_VALUES=""; \
	if [ -f values.local.yaml ]; then EXTRA_VALUES="--values values.local.yaml"; fi; \
	@$(HELM) upgrade --install my-operator charts/my-operator/ \
		--namespace $(NAMESPACE) \
		--values charts/my-operator/values.yaml \
		$$EXTRA_VALUES \
		--wait --timeout 2m
	@echo "Deploy OK"

.PHONY: undeploy
undeploy:
	@echo "Uninstalling Helm release..."
	@$(HELM) uninstall my-operator --namespace $(NAMESPACE) 2>/dev/null || true

.PHONY: logs
logs:
	$(KUBECTL) logs -n $(NAMESPACE) -l app=my-operator -f

.PHONY: status
status:
	@$(KUBECTL) get pods -n $(NAMESPACE) -l app=my-operator
	@echo ""
	@$(HELM) list -n $(NAMESPACE)

.PHONY: cs
cs:
	./gradlew ktlintCheck

.PHONY: cs-fix
cs-fix:
	./gradlew ktlintFormat
