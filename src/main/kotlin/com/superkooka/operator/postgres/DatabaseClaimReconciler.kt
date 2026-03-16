package com.superkooka.operator.postgres

import io.fabric8.kubernetes.api.model.Condition
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import java.security.SecureRandom
import java.sql.DriverManager
import java.sql.SQLException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
        val status = resource.status ?: DatabaseClaimStatus()
        resource.status = status
        status.observedGeneration = resource.metadata.generation

        val name = resource.metadata.name
        val namespace = resource.metadata.namespace
        val spec =
            resource.spec ?: run {
                logger.error { "DatabaseClaim '$name' has no spec" }
                status.phase = "Failed"
                status.message = "Missing spec"
                updateCondition(resource, "Ready", "False", "MissingSpec", "DatabaseClaim has no spec")
                return UpdateControl.patchStatus(resource)
            }

        val service =
            client
                .services()
                .inNamespace(namespace)
                .withName(spec.postgresRef.name)
                .get() ?: run {
                logger.error { "Service '${spec.postgresRef.name}' not found" }
                status.phase = "Failed"
                status.message = "Service '${spec.postgresRef.name}' not found"
                updateCondition(resource, "Ready", "False", "ServiceNotFound", "Service '${spec.postgresRef.name}' not found")
                return UpdateControl.patchStatus(resource)
            }

        val secret =
            client
                .secrets()
                .inNamespace(namespace)
                .withName(spec.credentialsRef.name)
                .get() ?: run {
                logger.error { "Secret '${spec.credentialsRef.name}' not found" }
                status.phase = "Failed"
                status.message = "Secret '${spec.credentialsRef.name}' not found"
                updateCondition(resource, "Ready", "False", "SecretNotFound", "Secret '${spec.credentialsRef.name}' not found")
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
        status.phase = "Running"
        status.message = "Database $name is ready"
        updateCondition(resource, "DatabaseReady", "True", "Success", "Database created successfully")

        val random = SecureRandom()
        val rolePassword =
            Base64
                .getEncoder()
                .withoutPadding()
                .encodeToString(
                    random
                        .generateSeed(32),
                )
        createRole(host, port, username, password, spec.database, spec.owner, rolePassword)

        val roleSecret =
            SecretBuilder()
                .withNewMetadata()
                .withName("$name-credentials")
                .withNamespace(namespace)
                .addToLabels("app.kubernetes.io/managed-by", "postgres-operator")
                .addToLabels("app.kubernetes.io/name", "postgres-credentials")
                .addToLabels("superkooka.com/database-claim", name)
                .addToAnnotations("superkooka.com/owner-role", spec.owner)
                .addToAnnotations("superkooka.com/database", spec.database)
                .withOwnerReferences(
                    OwnerReferenceBuilder()
                        .withApiVersion(resource.apiVersion)
                        .withKind(resource.kind)
                        .withName(name)
                        .withUid(resource.metadata.uid)
                        .withBlockOwnerDeletion(true)
                        .withController(true)
                        .build(),
                ).endMetadata()
                .addToData("username", Base64.getEncoder().encodeToString(spec.owner.toByteArray()))
                .addToData("password", Base64.getEncoder().encodeToString(rolePassword.toByteArray()))
                .addToData("host", Base64.getEncoder().encodeToString(host.toByteArray()))
                .addToData("port", Base64.getEncoder().encodeToString(port.toString().toByteArray()))
                .addToData("database", Base64.getEncoder().encodeToString(spec.database.toByteArray()))
                .build()

        client
            .secrets()
            .inNamespace(namespace)
            .resource(roleSecret)
            .serverSideApply()

        status.phase = "Ready"
        status.message = "Role for $name is ready"
        status.connectionSecret = "$name-credentials"
        updateCondition(resource, "Ready", "True", "Success", "Database and Role are ready")

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

    fun createRole(
        host: String,
        port: Int,
        adminUser: String,
        adminPassword: String,
        dbName: String,
        roleName: String,
        rolePassword: String,
    ) {
        val maintenanceUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(maintenanceUrl, adminUser, adminPassword).use { conn ->
            conn.autoCommit = true

            val roleExists =
                conn
                    .prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")
                    .use { stmt ->
                        stmt.setString(1, roleName)
                        stmt.executeQuery().next()
                    }

            if (!roleExists) {
                conn.createStatement().execute(
                    """CREATE ROLE "$roleName" WITH LOGIN PASSWORD ${conn.escapeString(rolePassword)}""",
                )
                logger.info { "Role '$roleName' created" }
            } else {
                logger.info { "Role '$roleName' already exists, skipping creation" }
            }
        }

        val dbUrl = "jdbc:postgresql://$host:$port/$dbName"
        DriverManager.getConnection(dbUrl, adminUser, adminPassword).use { conn ->
            conn.autoCommit = true

            require(dbName.matches(Regex("^[a-zA-Z0-9_]{1,63}$"))) { "Invalid dbName: $dbName" }
            require(roleName.matches(Regex("^[a-zA-Z0-9_]{1,63}$"))) { "Invalid roleName: $roleName" }

            val grants =
                listOf(
                    """GRANT USAGE ON SCHEMA public TO "$roleName"""",
                    """GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "$roleName"""", // SHOULD BE CONFIGURABLE
                    """GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "$roleName"""",
                    """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "$roleName"""",
                    """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "$roleName"""",
                )

            try {
                grants.forEach { sql -> conn.createStatement().execute(sql) }
            } catch (e: SQLException) {
                logger.error { "Failed to grant privileges on '$dbName' to '$roleName': ${e.message}" }
                throw e
            }

            logger.info { "Privileges on '$dbName' granted to '$roleName'" }
        }
    }

    private fun java.sql.Connection.escapeString(value: String): String {
        val escaped = value.replace("'", "''")
        return "'$escaped'"
    }

    private fun updateCondition(
        resource: DatabaseClaim,
        type: String,
        status: String,
        reason: String,
        message: String,
    ) {
        val conditions = resource.status.conditions
        val existing = conditions.find { it.type == type }

        if (existing == null || existing.status != status || existing.reason != reason || existing.message != message) {
            val now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
            val newCondition =
                Condition().apply {
                    this.type = type
                    this.status = status
                    this.reason = reason
                    this.message = message
                    this.lastTransitionTime = now
                }
            conditions.removeIf { it.type == type }
            conditions.add(newCondition)
        }
    }
}
