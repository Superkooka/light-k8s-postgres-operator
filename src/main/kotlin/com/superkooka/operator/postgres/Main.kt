package com.superkooka.operator.postgres

import com.superkooka.operator.postgres.kubernetes.PostgresConnectionFactoryProvider
import com.superkooka.operator.postgres.reconcilier.DatabaseClaimReconciler
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javaoperatorsdk.operator.Operator
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

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

    val healthPort = System.getenv("OPERATOR_HEALTH_PORT").toIntOrNull() ?: 8080
    val server =
        embeddedServer(Netty, port = healthPort) {
            routing {
                get("/healthz") {
                    call.respondText("OK")
                }
                get("/readyz") {
                    call.respondText("READY")
                }
            }
        }

    server.start(wait = false)

    operator.start()

    logger.info { "Operator started, watching DatabaseClaim resources" }

    Thread.currentThread().join()
}
