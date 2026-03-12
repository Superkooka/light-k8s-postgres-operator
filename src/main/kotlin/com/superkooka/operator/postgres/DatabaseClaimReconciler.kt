package com.superkooka.operator.postgres

import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import java.sql.DriverManager
import java.util.Base64

private val logger = KotlinLogging.logger {}

@ControllerConfiguration
class DatabaseClaimReconciler(
    private val client: KubernetesClient,
) : Reconciler<DatabaseClaim> {
    override fun reconcile(
        resource: DatabaseClaim,
        context: Context<DatabaseClaim>,
    ): UpdateControl<DatabaseClaim> {
        val name = resource.metadata.name
        val namespace = resource.metadata.namespace
        val spec =
            resource.spec ?: run {
                logger.error { "DatabaseClaim '$name' has no spec" }
                resource.status =
                    DatabaseClaimStatus().apply {
                        phase = "Failed"
                        message = "Missing spec"
                    }
                return UpdateControl.patchStatus(resource)
            }

        val service =
            client
                .services()
                .inNamespace(namespace)
                .withName(spec.postgresRef.name)
                .get() ?: run {
                logger.error { "Service '${spec.postgresRef.name}' not found" }
                resource.status =
                    DatabaseClaimStatus().apply {
                        phase = "Failed"
                        message = "Service '${spec.postgresRef.name}' not found"
                    }
                return UpdateControl.patchStatus(resource)
            }

        val secret =
            client
                .secrets()
                .inNamespace(namespace)
                .withName(spec.credentialsRef.name)
                .get() ?: run {
                logger.error { "Secret '${spec.credentialsRef.name}' not found" }
                resource.status =
                    DatabaseClaimStatus().apply {
                        phase = "Failed"
                        message = "Secret '${spec.credentialsRef.name}' not found"
                    }
                return UpdateControl.patchStatus(resource)
            }

        val host = "${service.metadata.name}.${service.metadata.namespace}.svc.cluster.local"
        val port =
            service.spec.ports
                .first()
                .port
        val username = String(Base64.getDecoder().decode(secret.data["username"]))
        val password = String(Base64.getDecoder().decode(secret.data["password"]))

        logger.info { "Reconciling DatabaseClaim '$name' in '$namespace'" }

        createDatabase(host, port, username, password, spec.database)
        resource.status =
            DatabaseClaimStatus().apply {
                phase = "Running"
                message = "Database $name is ready"
            }

        // createRole()

        // resource.status = DatabaseClaimStatus().apply {
        //     phase = "Ready"
        //     message = "Role for $databaseName is ready"
        // }

        logger.info { "DatabaseClaim '$name' reconciled successfully" }

        return UpdateControl.patchStatus(resource)
    }

    fun createDatabase(
        host: String,
        port: Int,
        adminUser: String,
        adminPassword: String,
        dbName: String,
    ) {
        val url = "jdbc:postgresql://$host:$port/postgres"

        DriverManager.getConnection(url, adminUser, adminPassword).use { conn ->
            conn.autoCommit = true

            val exists =
                conn
                    .prepareStatement(
                        "SELECT 1 FROM pg_database WHERE datname = ?",
                    ).use { stmt ->
                        stmt.setString(1, dbName)
                        stmt.executeQuery().next()
                    }

            if (!exists) {
                conn.createStatement().execute("""CREATE DATABASE "$dbName"""")
            }
        }
    }
}
