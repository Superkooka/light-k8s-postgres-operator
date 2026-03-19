package com.superkooka.operator.postgres.kubernetes

import com.superkooka.operator.postgres.api.PostgresInstance
import com.superkooka.operator.postgres.postgres.PostgresAdminCredentials
import com.superkooka.operator.postgres.postgres.PostgresConnectionFactory
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.Base64

class PostgresConnectionFactoryProvider(
    private val client: KubernetesClient,
) {
    fun getFactory(instance: PostgresInstance): PostgresConnectionFactory {
        val instanceNamespace = instance.metadata.namespace
        val spec = instance.spec ?: throw IllegalStateException("PostgresInstance '${instance.metadata.name}' has no spec")

        val serviceRef = spec.postgresServiceRef
        val serviceNamespace = serviceRef.namespace ?: instanceNamespace
        val service =
            client
                .services()
                .inNamespace(serviceNamespace)
                .withName(serviceRef.name)
                .get()
                ?: throw IllegalStateException("Service '${serviceRef.name}' not found in namespace '$serviceNamespace'")

        val credentialsRef = spec.credentialsRef
        val credentialsNamespace = credentialsRef.namespace ?: instanceNamespace
        val adminSecret =
            client
                .secrets()
                .inNamespace(credentialsNamespace)
                .withName(credentialsRef.name)
                .get()
                ?: throw IllegalStateException("Secret '${credentialsRef.name}' not found in namespace '$credentialsNamespace'")

        val host = "${service.metadata.name}.${service.metadata.namespace}.svc.cluster.local"
        val port =
            service.spec.ports
                .first()
                .port
        val adminUsername = "postgres"
        val adminPassword = String(Base64.getDecoder().decode(adminSecret.data["postgres-password"]))

        return PostgresConnectionFactory(
            PostgresAdminCredentials(host, port, adminUsername, adminPassword),
        )
    }
}
