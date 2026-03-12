# k8s-postgres-operator

K8S Operator for postresql database.
Write for my personal homelab, open to PR. Does not claim to be complete.

Create database and role. Put credential on secret.

ci+renovate, backups and internal metrics are scheduled

Test strategie
\unit tests: reconciler logic, mocked K8s client
\e2e chainsaw: happy scnario, observable cluster
\e2e kotlin: edge cases, resiliency
