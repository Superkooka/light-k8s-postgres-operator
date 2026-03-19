package com.superkooka.operator.postgres.reconcilier

import com.superkooka.operator.postgres.api.DatabaseClaim
import com.superkooka.operator.postgres.api.DatabaseClaimStatus
import com.superkooka.operator.postgres.api.PostgresInstance
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

        val instanceRef = spec.instanceRef
        val instanceNamespace = instanceRef.namespace ?: namespace
        val instance =
            client
                .resources(PostgresInstance::class.java)
                .inNamespace(instanceNamespace)
                .withName(instanceRef.name)
                .get() ?: run {
                logger.error { "PostgresInstance '${instanceRef.name}' not found in '$instanceNamespace'" }
                return failWith(resource, "InstanceNotFound", "PostgresInstance '${instanceRef.name}' not found")
            }

        val instanceSpec =
            instance.spec ?: run {
                return failWith(resource, "InstanceMissingSpec", "PostgresInstance '${instanceRef.name}' has no spec")
            }

        val serviceRef = instanceSpec.postgresServiceRef
        val serviceNamespace = serviceRef.namespace ?: instanceNamespace
        val service =
            client
                .services()
                .inNamespace(serviceNamespace)
                .withName(serviceRef.name)
                .get() ?: run {
                logger.error { "Service '${serviceRef.name}' not found in '$serviceNamespace'" }
                return failWith(resource, "ServiceNotFound", "Service '${serviceRef.name}' not found")
            }

        val credentialsRef = instanceSpec.credentialsRef
        val credentialsNamespace = credentialsRef.namespace ?: instanceNamespace
        val adminSecret =
            client
                .secrets()
                .inNamespace(credentialsNamespace)
                .withName(credentialsRef.name)
                .get() ?: run {
                logger.error { "Secret '${credentialsRef.name}' not found in '$credentialsNamespace'" }
                return failWith(resource, "SecretNotFound", "Secret '${credentialsRef.name}' not found")
            }

        val host = "${service.metadata.name}.${service.metadata.namespace}.svc.cluster.local"
        val port =
            service.spec.ports
                .first()
                .port
        val adminUsername = String(Base64.getDecoder().decode(adminSecret.data["username"]))
        val adminPassword = String(Base64.getDecoder().decode(adminSecret.data["password"]))

        logger.info { "Reconciling DatabaseClaim '$name' in '$namespace'" }

        val connectionFactory =
            PostgresConnectionFactory(
                PostgresAdminCredentials(host, port, adminUsername, adminPassword),
            )

        val databaseProvisioner = DatabaseProvisioner(connectionFactory)
        try {
            databaseProvisioner.ensureDatabase(spec.database)
        } catch (e: ProvisioningException) {
            logger.error { e.message }
            return failWith(resource, e.error.name, e.message)
        }

        updateCondition(resource, "DatabaseReady", "True", "Success", "Database created successfully")

        val roleProvisioner = RoleProvisioner(connectionFactory)
        val ownerRef =
            OwnerReferenceBuilder()
                .withApiVersion(resource.apiVersion)
                .withKind(resource.kind)
                .withName(name)
                .withUid(resource.metadata.uid)
                .withBlockOwnerDeletion(true)
                .withController(true)
                .build()

        for (user in spec.users) {
            val existingSecret =
                client
                    .secrets()
                    .inNamespace(namespace)
                    .withName(user.secretName)
                    .get()

            val rolePassword =
                if (existingSecret != null) {
                    String(Base64.getDecoder().decode(existingSecret.data["password"]))
                } else {
                    Base64.getEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(32))
                }

            try {
                roleProvisioner.ensureRole(user.name, rolePassword, spec.database, user.permissions)
            } catch (e: ProvisioningException) {
                logger.error { e.message }
                return failWith(resource, e.error.name, e.message)
            }

            val userSecret =
                SecretBuilder()
                    .withNewMetadata()
                    .withName(user.secretName)
                    .withNamespace(namespace)
                    .addToLabels("app.kubernetes.io/managed-by", "postgres-operator")
                    .addToLabels("superkooka.com/database-claim", name)
                    .addToAnnotations("superkooka.com/user", user.name)
                    .addToAnnotations("superkooka.com/database", spec.database)
                    .withOwnerReferences(ownerRef)
                    .endMetadata()
                    .addToData("username", Base64.getEncoder().encodeToString(user.name.toByteArray()))
                    .addToData("password", Base64.getEncoder().encodeToString(rolePassword.toByteArray()))
                    .addToData("host", Base64.getEncoder().encodeToString(host.toByteArray()))
                    .addToData("port", Base64.getEncoder().encodeToString(port.toString().toByteArray()))
                    .addToData("database", Base64.getEncoder().encodeToString(spec.database.toByteArray()))
                    .build()

            client
                .secrets()
                .inNamespace(namespace)
                .resource(userSecret)
                .serverSideApply()
            logger.info { "Secret '${user.secretName}' provisioned for user '${user.name}'" }
        }

        status.phase = "Ready"
        status.message = "DatabaseClaim '$name' is ready"
        updateCondition(resource, "Ready", "True", "Success", "Database and users are ready")

        logger.info { "DatabaseClaim '$name' reconciled successfully" }
        return UpdateControl.patchStatus(resource)
    }
}
