# k8s-postgres-operator

K8S Operator for postresql database.
Write for my personal homelab, open to PR. Does not claim to be complete.

Create database and role. Put credential on secret.

ci+renovate, backups and internal metrics are scheduled

Test strategie
unit tests: reconciler logic, mocked K8s client\
e2e chainsaw: happy scnario, observable cluster\
e2e kotlin: edge cases, resiliency


## Dev env

`make check-dependencies` to ensure docker/kubectl/helm/k3d/java are detected\
`make init` to setup the project\
`make run` to run a k3d cluster with the operator inside. ./local.values.yaml is used.


`make stop` to stop the k3d cluster\
`make deploy` to re-deploy the build/helm chart\
`make undeploy` to uninstall helm release\
`make logs` to get kunectl log on operator\
`make status` to get pod operator status


`make syncHelmVersion` to sync the helm image tag with project version\
`make syncCrds` to copy generated crds inside helm chart\
`make cs-check` to run linter (dryrun)\
`make cs-fix` to run linter


`KUBECONFIG=$(k3d kubeconfig write light-k8s-postgres-operator) k9s` Launch k9s connected to the dev cluster