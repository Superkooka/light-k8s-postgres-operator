package com.superkooka.operator.postgres.reconcilier

import com.superkooka.operator.postgres.api.DatabaseClaim
import com.superkooka.operator.postgres.api.DatabaseClaimStatus
import com.superkooka.operator.postgres.postgres.DatabaseProvisioner
import com.superkooka.operator.postgres.postgres.PostgresAdminCredentials
import com.superkooka.operator.postgres.postgres.PostgresConnectionFactory
import com.superkooka.operator.postgres.postgres.ProvisioningException
import com.superkooka.operator.postgres.postgres.RoleProvisioner
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import java.security.SecureRandom
import java.util.Base64

private val logger = KotlinLogging.logger {}

@ControllerConfiguration
class DatabaseClaimReconciler(
    private val client: KubernetesClient,
) : AbstractReconciler<DatabaseClaim>() {
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
                return failWith(resource, "MissingSpec", "DatabaseClaim has no spec")
            }

        val service =
            client
                .services()
                .inNamespace(namespace)
                .withName(spec.postgresRef.name)
                .get() ?: run {
                logger.error { "Service '${spec.postgresRef.name}' not found" }
                return failWith(resource, "ServiceNotFound", "Service '${spec.postgresRef.name}' not found")
            }

        val secret =
            client
                .secrets()
                .inNamespace(namespace)
                .withName(spec.credentialsRef.name)
                .get() ?: run {
                logger.error { "Secret '${spec.credentialsRef.name}' not found" }
                return failWith(resource, "SecretNotFound", "Secret '${spec.credentialsRef.name}' not found")
            }

        val host = "${service.metadata.name}.${service.metadata.namespace}.svc.cluster.local"
        val port = // TODO FIX ME: Can crash if no port?
            service.spec.ports
                .first()
                .port
        val username = String(Base64.getDecoder().decode(secret.data["username"]))
        val password = String(Base64.getDecoder().decode(secret.data["password"]))

        logger.info { "Reconciling DatabaseClaim '$name' in '$namespace'" }

        val postgresAdminDatabaseFactory =
            PostgresConnectionFactory(
                PostgresAdminCredentials(
                    host,
                    port,
                    username,
                    password,
                ),
            )

        val databaseProvisioner = DatabaseProvisioner(postgresAdminDatabaseFactory)
        try {
            databaseProvisioner.ensureDatabase(spec.database)
        } catch (e: ProvisioningException) {
            logger.error { e.message }
            return failWith(resource, e.error.name, e.message)
        }

        status.phase = "Running"
        status.message = "Database $name is ready"
        updateCondition(resource, "DatabaseReady", "True", "Success", "Database created successfully")

        // TODO: Explicit strategy for password
        // Not handled: Secret missing  + existing role

        val secretName = "$name-credentials"
        val existingSecret =
            client
                .secrets()
                .inNamespace(namespace)
                .withName(secretName)
                .get()
        val rolePassword =
            if (existingSecret != null) {
                String(Base64.getDecoder().decode(existingSecret.data["password"]))
            } else {
                Base64.getEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(32))
            }

        val roleProvisioner = RoleProvisioner(postgresAdminDatabaseFactory)
        roleProvisioner.ensureRole(spec.owner, rolePassword, spec.database)

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
}
