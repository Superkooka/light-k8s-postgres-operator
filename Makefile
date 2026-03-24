VERSION := $(shell grep '^version=' gradle.properties | head -1 | sed 's/version=//')
IMAGE     := light-k8s-postgres-operator
CLUSTER   := light-k8s-postgres-operator-dev
CONTEXT   := k3d-$(CLUSTER)
NAMESPACE := default
KUBECTL   := kubectl --context=$(CONTEXT)
HELM      := helm --kube-context=$(CONTEXT)
CHART_NAME := light-k8s-postgres-operator
GRADLEW_EXTRA_ARGUMENTS := -PchartsDirectory=charts/

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

.PHONY: init
init:
	@if [ ! -f values.local.yaml ]; then \
        cp charts/values.local.yaml ./values.local.yaml; \
        echo "values.local.yaml created from example, edit it before running."; \
	fi
	
.PHONY: run
run: check-dependencies cluster-up build syncCrds deploy logs

.PHONY: stop
stop: cluster-down

.PHONY: build
build:
	./gradlew build $(GRADLEW_EXTRA_ARGUMENTS)

.PHONY: test
test:
	./gradlew cleanTest test $(GRADLEW_EXTRA_ARGUMENTS)

.PHONY: image
image:
	./gradlew jibDockerBuild $(GRADLEW_EXTRA_ARGUMENTS)

.PHONY: syncCrds
syncCrds:
	@echo "Syncing CRDs..."
	./gradlew syncCrds $(GRADLEW_EXTRA_ARGUMENTS)
	@echo "Sync OK"

.PHONY: cluster-up
cluster-up:
	@if k3d cluster list 2>/dev/null | grep -q "^$(CLUSTER)"; then \
		echo "Cluster '$(CLUSTER)' already exists, skipping."; \
	else \
		echo "Creating k3d cluster '$(CLUSTER)'..."; \
		k3d cluster create $(CLUSTER) --agents 1 --kubeconfig-update-default=false --kubeconfig-switch-context=false; \
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
deploy: syncCrds image _check-context
	@echo "Importing image into k3d..."
	k3d image import $(IMAGE):$(VERSION) --cluster $(CLUSTER)
	@echo "Deploying via Helm..."
	@EXTRA_VALUES=""; \
	$(HELM) dependency update charts/
	$(KUBECTL) apply -f charts/crds/
	$(HELM) upgrade --install $(CHART_NAME) charts/ \
		--namespace $(NAMESPACE) \
		--values charts/values.yaml \
		--values ./values.local.yaml \
		--wait --timeout 2m
	@echo "Deploy OK"

.PHONY: undeploy
undeploy:
	@echo "Uninstalling Helm release..."
	@$(HELM) uninstall $(CHART_NAME) --namespace $(NAMESPACE) 2>/dev/null || true

.PHONY: logs
logs:
	$(KUBECTL) logs -n $(NAMESPACE) -l app=$(CHART_NAME) -f

.PHONY: status
status:
	@$(KUBECTL) get pods -n $(NAMESPACE) -l app=$(CHART_NAME)
	@echo ""
	@$(HELM) list -n $(NAMESPACE)

.PHONY: cs
cs:
	./gradlew ktlintCheck $(GRADLEW_EXTRA_ARGUMENTS)

.PHONY: cs-fix
cs-fix:
	./gradlew ktlintFormat $(GRADLEW_EXTRA_ARGUMENTS)
