package com.superkooka.operator.postgres

import com.superkooka.operator.postgres.kubernetes.PostgresConnectionFactoryProvider
import com.superkooka.operator.postgres.reconcilier.DatabaseClaimReconciler
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javaoperatorsdk.operator.Operator

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting postgres operator..." }

    val client = KubernetesClientBuilder().build()
    val connectionFactoryProvider = PostgresConnectionFactoryProvider(client)

    val operator =
        Operator { overrider ->
            overrider.withKubernetesClient(client)
        }

    operator.register(
        DatabaseClaimReconciler(
            client,
            connectionFactoryProvider,
        ),
    )
    // operator.installShutdownHook()
    operator.start()

    logger.info { "Operator started, watching DatabaseClaim resources" }
}
