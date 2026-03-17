package com.superkooka.operator.postgres.postgres

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
    ) {
        connectionFactory.connect().use { conn ->
            if (!roleExists(conn, roleName)) {
                createRole(conn, roleName, rolePassword)
            } else {
                logger.info { "Role '$roleName' already exists, skipping creation" }
            }
        }

        connectionFactory.connect(dbName).use { conn ->
            grantPrivileges(conn, roleName, dbName)
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
    ) {
        val grants =
            listOf(
                """GRANT USAGE ON SCHEMA public TO "${roleName.validateIdentifier()}"""",
                """GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${roleName.validateIdentifier()}"""",
                """GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${roleName.validateIdentifier()}"""",
                """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${roleName.validateIdentifier()}"""",
                """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "${roleName.validateIdentifier()}"""",
            )

        try {
            grants.forEach { sql -> conn.createStatement().execute(sql) }
            logger.info { "Privileges on '$dbName' granted to '$roleName'" }
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