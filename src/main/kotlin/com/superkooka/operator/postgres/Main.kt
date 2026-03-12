package com.superkooka.operator.postgres

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javaoperatorsdk.operator.Operator

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting postgres operator..." }

    val client = KubernetesClientBuilder().build()
    val operator =
        Operator { overrider ->
            overrider.withKubernetesClient(client)
        }

    operator.register(DatabaseClaimReconciler(client))
    // operator.installShutdownHook()
    operator.start()

    logger.info { "Operator started, watching DatabaseClaim resources" }
}
