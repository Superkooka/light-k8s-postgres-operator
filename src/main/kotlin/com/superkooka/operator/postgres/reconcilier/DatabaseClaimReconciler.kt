package com.superkooka.operator.postgres.reconcilier

import com.superkooka.operator.postgres.api.DatabaseClaim
import com.superkooka.operator.postgres.api.DatabaseClaimStatus
import com.superkooka.operator.postgres.api.PostgresInstance
import com.superkooka.operator.postgres.kubernetes.PostgresConnectionFactoryProvider
import com.superkooka.operator.postgres.postgres.DatabaseProvisioner
import com.superkooka.operator.postgres.postgres.Permission
import com.superkooka.operator.postgres.postgres.ProvisioningException
import com.superkooka.operator.postgres.postgres.RoleProvisioner
import com.superkooka.operator.postgres.postgres.SchemaProvisioner
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
    private val connectionFactoryProvider: PostgresConnectionFactoryProvider,
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
        val spec = resource.spec ?: return failWith(resource, "MissingSpec", "DatabaseClaim $name has no spec")

        val instanceRef = spec.instanceRef
        val instanceNamespace = instanceRef.namespace ?: namespace
        val instance =
            client
                .resources(PostgresInstance::class.java)
                .inNamespace(instanceNamespace)
                .withName(instanceRef.name)
                .get()
                ?: return failWith(resource, "InstanceNotFound", "PostgresInstance '${instanceRef.name}' not found")

        val connectionFactory =
            try {
                connectionFactoryProvider.getFactory(instance)
            } catch (e: Exception) {
                return failWith(resource, "ConnectionError", e.message ?: "Failed to get connection")
            }

        val userPasswords =
            spec.users.associate { user ->
                val userSecret =
                    client
                        .secrets()
                        .inNamespace(namespace)
                        .withName(user.secretName)
                        .get()
                val userPassword =
                    userSecret?.let { String(Base64.getDecoder().decode(it.data["password"])) }
                        ?: Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(32))
                user.name to userPassword
            }

        val databaseProvisioner = DatabaseProvisioner()
        val roleProvisioner = RoleProvisioner()
        val schemaProvisioner = SchemaProvisioner()

        try {
            databaseProvisioner.ensureDatabase(connectionFactory, spec.database)

            val ownerRole = "${spec.database}__owner"
            roleProvisioner.ensureRole(connectionFactory, ownerRole, login = false)
            roleProvisioner.grantDatabasePermissions(
                connectionFactory,
                ownerRole,
                spec.database,
                Permission.DATABASE_ALL,
            )

            spec.schemas.forEach { schema ->
                schemaProvisioner.ensureSchema(connectionFactory, spec.database, schema.name, ownerRole)
                logger.info { "Schema '${schema.name}' provisioned in database '${spec.database}'" }
            }

            spec.roles.forEach { roleSpec ->
                roleProvisioner.ensureRole(connectionFactory, roleSpec.name, login = false)

                spec.schemas.forEach { schema ->
                    schemaProvisioner.revokeSchemaPrivileges(
                        connectionFactory,
                        spec.database,
                        schema.name,
                        roleSpec.name,
                        Permission.ALL,
                    )
                    schemaProvisioner.revokeDefaultPrivileges(
                        connectionFactory,
                        spec.database,
                        schema.name,
                        ownerRole,
                        roleSpec.name,
                        Permission.ALL,
                    )
                }

                roleSpec.schemas.forEach { (schemaName, schemaPerms) ->
                    schemaProvisioner.grantSchemaPrivileges(
                        connectionFactory,
                        spec.database,
                        schemaName,
                        roleSpec.name,
                        schemaPerms.permissions,
                    )
                    schemaProvisioner.applyDefaultPrivileges(
                        connectionFactory,
                        spec.database,
                        schemaName,
                        ownerRole,
                        roleSpec.name,
                        schemaPerms.permissions,
                    )
                }
                logger.info { "Role '${roleSpec.name}' provisioned for database '${spec.database}'" }
            }

            spec.users.forEach { userSpec ->
                val password =
                    userPasswords[userSpec.name] ?: throw IllegalArgumentException("Missing password for user '${userSpec.name}'")
                roleProvisioner.ensureRole(connectionFactory, userSpec.name, login = true, password = password)

                roleProvisioner.revokeDatabasePermissions(
                    connectionFactory,
                    userSpec.name,
                    spec.database,
                    Permission.DATABASE_ALL,
                )

                spec.roles.forEach { roleSpec ->
                    roleProvisioner.revokeRole(connectionFactory, roleSpec.name, userSpec.name)
                }

                roleProvisioner.grantDatabasePermissions(
                    connectionFactory,
                    userSpec.name,
                    spec.database,
                    listOf(Permission.CONNECT),
                )

                userSpec.roles.forEach { roleName ->
                    roleProvisioner.grantRole(connectionFactory, roleName, userSpec.name)
                }
                logger.info { "User '${userSpec.name}' provisioned and granted roles/permissions" }
            }
        } catch (e: ProvisioningException) {
            return failWith(resource, e.error.name, e.message)
        } catch (e: Exception) {
            return failWith(resource, "ProvisioningError", e.message ?: "Unknown provisioning error")
        }

        updateCondition(resource, "DatabaseReady", "True", "Success", "Database and roles provisioned")

        val ownerRef =
            OwnerReferenceBuilder()
                .withApiVersion(resource.apiVersion)
                .withKind(resource.kind)
                .withName(name)
                .withUid(resource.metadata.uid)
                .withBlockOwnerDeletion(true)
                .withController(true)
                .build()

        val creds = connectionFactory.credentials
        spec.users.forEach { user ->
            val password = userPasswords[user.name]!!
            val secret =
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
                    .addToData("password", Base64.getEncoder().encodeToString(password.toByteArray()))
                    .addToData("host", Base64.getEncoder().encodeToString(creds.host.toByteArray()))
                    .addToData("port", Base64.getEncoder().encodeToString(creds.port.toString().toByteArray()))
                    .addToData("database", Base64.getEncoder().encodeToString(spec.database.toByteArray()))
                    .build()

            client
                .secrets()
                .inNamespace(namespace)
                .resource(secret)
                .serverSideApply()
            logger.info { "Secret '${user.secretName}' provisioned for user '${user.name}'" }
        }

        status.phase = "Ready"
        status.message = "DatabaseClaim '$name' is ready"
        updateCondition(resource, "Ready", "True", "Success", "Database and users are ready")

        return UpdateControl.patchStatus(resource)
    }
}
