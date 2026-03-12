package com.superkooka.operator.postgres

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.Operator
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting postgres operator..." }

    val client = KubernetesClientBuilder().build()
    val operator = Operator(client) { config ->
        config.withHealthProbePort(
            System.getenv("OPERATOR_HEALTH_PORT")?.toInt() ?: 8080
        )
        config.withMetricsPort(
            System.getenv("OPERATOR_METRICS_PORT")?.toInt() ?: 8081
        )
        config.withLeaderElectionEnabled(
            System.getenv("OPERATOR_LEADER_ELECTION")?.toBoolean() ?: false
        )
    }

    operator.register(DatabaseClaimReconciler())
    operator.installShutdownHook()
    operator.start()

    logger.info { "Operator started, watching DatabaseClaim resources" }
}
