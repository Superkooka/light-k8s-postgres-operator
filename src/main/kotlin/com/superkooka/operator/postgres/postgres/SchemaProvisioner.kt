package com.superkooka.operator.postgres.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

class SchemaProvisioner {
    fun ensureSchema(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
        schema: String,
        owner: String,
    ) {
        try {
            connectionFactory.connect(dbName).use { conn ->
                conn.createStatement().execute(
                    """CREATE SCHEMA IF NOT EXISTS "${schema.validateIdentifier()}" AUTHORIZATION "${owner.validateIdentifier()}"""",
                )
                logger.info { "Schema '$schema' created/ensured in database '$dbName' with owner '$owner'" }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.SCHEMA_CREATE_FAILED,
                "Failed to ensure schema '$schema' in database '$dbName'",
                e,
            )
        }
    }

    fun grantSchemaPrivileges(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
        schema: String,
        role: String,
        permissions: List<Permission>,
    ) {
        try {
            connectionFactory.connect(dbName).use { conn ->
                conn.transaction {
                    if (permissions.isNotEmpty()) {
                        exec(conn, """GRANT USAGE ON SCHEMA "${schema.validateIdentifier()}" TO "${role.validateIdentifier()}"""")
                    }

                    if (Permission.CREATE in permissions) {
                        exec(conn, """GRANT CREATE ON SCHEMA "${schema.validateIdentifier()}" TO "${role.validateIdentifier()}"""")
                    }

                    val tablePrivileges = permissions.mapNotNull { it.toTablePrivilege() }.joinToString(", ")

                    if (tablePrivileges.isNotEmpty()) {
                        exec(
                            conn,
                            """GRANT $tablePrivileges ON ALL TABLES IN SCHEMA "${schema.validateIdentifier()}" TO "${role.validateIdentifier()}"""",
                        )
                    }

                    if (permissions.any { it in setOf(Permission.INSERT, Permission.UPDATE) }) {
                        exec(
                            conn,
                            """GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "${schema.validateIdentifier()}" TO "${role.validateIdentifier()}"""",
                        )
                    }
                }
                logger.info { "Granted privileges to role '$role' on schema '$schema' in database '$dbName': $permissions" }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.SCHEMA_PRIVILEGES_GRANT_FAILED,
                "Failed to grant privileges to role '$role' on schema '$schema' in database '$dbName'",
                e,
            )
        }
    }

    fun revokeSchemaPrivileges(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
        schema: String,
        role: String,
        permissions: List<Permission>,
    ) {
        try {
            connectionFactory.connect(dbName).use { conn ->
                conn.transaction {
                    if (permissions.isNotEmpty()) {
                        exec(conn, """REVOKE USAGE ON SCHEMA "${schema.validateIdentifier()}" FROM "${role.validateIdentifier()}"""")
                    }

                    if (Permission.CREATE in permissions) {
                        exec(conn, """REVOKE CREATE ON SCHEMA "${schema.validateIdentifier()}" FROM "${role.validateIdentifier()}"""")
                    }

                    val tablePrivileges = permissions.mapNotNull { it.toTablePrivilege() }.joinToString(", ")

                    if (tablePrivileges.isNotEmpty()) {
                        exec(
                            conn,
                            """REVOKE $tablePrivileges ON ALL TABLES IN SCHEMA "${schema.validateIdentifier()}" FROM "${role.validateIdentifier()}"""",
                        )
                    }

                    if (permissions.any { it in setOf(Permission.INSERT, Permission.UPDATE) }) {
                        exec(
                            conn,
                            """REVOKE USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "${schema.validateIdentifier()}" FROM "${role.validateIdentifier()}"""",
                        )
                    }
                }
                logger.info { "Revoked privileges from role '$role' on schema '$schema' in database '$dbName': $permissions" }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.SCHEMA_PRIVILEGES_REVOKE_FAILED,
                "Failed to revoke privileges from role '$role' on schema '$schema' in database '$dbName'",
                e,
            )
        }
    }

    fun applyDefaultPrivileges(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
        schema: String,
        owner: String,
        role: String,
        permissions: List<Permission>,
    ) {
        val tablePrivileges = permissions.mapNotNull { it.toTablePrivilege() }.joinToString(", ")

        if (tablePrivileges.isEmpty()) return

        try {
            connectionFactory.connect(dbName).use { conn ->
                exec(
                    conn,
                    """
                    ALTER DEFAULT PRIVILEGES FOR ROLE "${owner.validateIdentifier()}"
                    IN SCHEMA "${schema.validateIdentifier()}"
                    GRANT $tablePrivileges ON TABLES TO "${role.validateIdentifier()}"
                    """.trimIndent(),
                )
                logger.info { "Applied default privileges for owner '$owner' on schema '$schema' to role '$role' in database '$dbName'" }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.DEFAULT_PRIVILEGES_GRANT_FAILED,
                "Failed to apply default privileges for owner '$owner' on schema '$schema' to role '$role' in database '$dbName'",
                e,
            )
        }
    }

    fun revokeDefaultPrivileges(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
        schema: String,
        owner: String,
        role: String,
        permissions: List<Permission>,
    ) {
        val tablePrivileges = permissions.mapNotNull { it.toTablePrivilege() }.joinToString(", ")

        if (tablePrivileges.isEmpty()) return

        try {
            connectionFactory.connect(dbName).use { conn ->
                exec(
                    conn,
                    """
                    ALTER DEFAULT PRIVILEGES FOR ROLE "${owner.validateIdentifier()}"
                    IN SCHEMA "${schema.validateIdentifier()}"
                    REVOKE $tablePrivileges ON TABLES FROM "${role.validateIdentifier()}"
                    """.trimIndent(),
                )
                logger.info { "Revoked default privileges for owner '$owner' on schema '$schema' from role '$role' in database '$dbName'" }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.DEFAULT_PRIVILEGES_REVOKE_FAILED,
                "Failed to revoke default privileges for owner '$owner' on schema '$schema' from role '$role' in database '$dbName'",
                e,
            )
        }
    }

    private fun exec(
        conn: Connection,
        sql: String,
    ) {
        conn.createStatement().execute(sql)
    }
}
