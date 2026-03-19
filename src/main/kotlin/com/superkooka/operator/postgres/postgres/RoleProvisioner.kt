package com.superkooka.operator.postgres.postgres

import com.superkooka.operator.postgres.api.Permission
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

class RoleProvisioner(
    private val connectionFactory: PostgresConnectionFactory,
) {
    fun ensureRole(
        roleName: String,
        rolePassword: String,
        dbName: String,
        permissions: List<Permission>,
    ) {
        connectionFactory.connect().use { conn ->
            if (!roleExists(conn, roleName)) {
                createRole(conn, roleName, rolePassword)
            } else {
                logger.info { "Role '$roleName' already exists, skipping creation" }
            }
        }

        connectionFactory.connect(dbName).use { conn ->
            grantPrivileges(conn, roleName, dbName, permissions)
        }
    }

    fun updatePassword(
        roleName: String,
        newPassword: String,
    ) {
        try {
            connectionFactory.connect().use { conn ->
                val quotedPassword = quoteLiteral(conn, newPassword)
                conn.createStatement().execute(
                    """ALTER ROLE "${roleName.validateIdentifier()}" WITH PASSWORD $quotedPassword""",
                )
                logger.info { "Password updated for role '$roleName'" }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_PASSWORD_UPDATE_FAILED,
                "Failed to update password for role '$roleName'",
                e,
            )
        }
    }

    private fun roleExists(
        conn: Connection,
        roleName: String,
    ): Boolean =
        conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { stmt ->
            stmt.setString(1, roleName)
            stmt.executeQuery().next()
        }

    private fun createRole(
        conn: Connection,
        roleName: String,
        rolePassword: String,
    ) {
        try {
            val quotedPassword = quoteLiteral(conn, rolePassword)
            conn.createStatement().execute(
                """CREATE ROLE "${roleName.validateIdentifier()}" WITH LOGIN PASSWORD $quotedPassword""",
            )
            logger.info { "Role '$roleName' created" }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_CREATE_FAILED,
                "Failed to create role '$roleName'",
                e,
            )
        }
    }

    private fun grantPrivileges(
        conn: Connection,
        roleName: String,
        dbName: String,
        permissions: List<Permission>,
    ) {
        val role = roleName.validateIdentifier()
        val grants = mutableListOf<String>()

        if (Permission.CONNECT in permissions) {
            grants += """GRANT CONNECT ON DATABASE "${dbName.validateIdentifier()}" TO "$role""""
        }

        val schemaPrivileges =
            permissions
                .mapNotNull { it.toSchemaPrivilege() }
                .joinToString(", ")

        if (schemaPrivileges.isNotEmpty()) {
            grants += """GRANT USAGE ON SCHEMA public TO "$role""""
            grants += """GRANT $schemaPrivileges ON ALL TABLES IN SCHEMA public TO "$role""""
            grants += """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT $schemaPrivileges ON TABLES TO "$role""""
        }

        if (permissions.any { it in listOf(Permission.SELECT, Permission.CREATE) }) {
            grants += """GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "$role""""
            grants += """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "$role""""
        }

        try {
            grants.forEach { sql -> conn.createStatement().execute(sql) }
            logger.info { "Privileges $permissions on '$dbName' granted to '$roleName'" }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_PRIVILEGES_GRANT_FAILED,
                "Failed to grant privileges on '$dbName' to '$roleName'",
                e,
            )
        }
    }

    private fun quoteLiteral(
        conn: Connection,
        value: String,
    ): String =
        conn.prepareStatement("SELECT quote_literal(?)").use { stmt ->
            stmt.setString(1, value)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private fun String.validateIdentifier(): String {
        require(this.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
            "Invalid identifier: $this"
        }
        return this
    }
}

private fun Permission.toSchemaPrivilege(): String? =
    when (this) {
        Permission.SELECT -> "SELECT"
        Permission.INSERT -> "INSERT"
        Permission.UPDATE -> "UPDATE"
        Permission.DELETE -> "DELETE"
        Permission.TRUNCATE -> "TRUNCATE"
        Permission.REFERENCES -> "REFERENCES"
        Permission.TRIGGER -> "TRIGGER"
        Permission.CREATE -> "CREATE"
        Permission.CONNECT -> null
    }
