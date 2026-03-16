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
            if (!roleExists(roleName)) {
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
        connectionFactory.connect().use { conn ->
            conn.createStatement().execute(
                """ALTER ROLE "$roleName" WITH PASSWORD ${conn.escapeString(newPassword)}""",
            )
            logger.info { "Password updated for role '$roleName'" }
        }
    }

    fun roleExists(roleName: String): Boolean =
        connectionFactory.connect().use { conn ->
            conn
                .prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")
                .use { stmt ->
                    stmt.setString(1, roleName)
                    stmt.executeQuery().next()
                }
        }

    private fun createRole(
        conn: Connection,
        roleName: String,
        rolePassword: String,
    ) {
        conn.createStatement().execute(
            """CREATE ROLE "$roleName" WITH LOGIN PASSWORD ${conn.escapeString(rolePassword)}""",
        )
        logger.info { "Role '$roleName' created" }
    }

    private fun grantPrivileges(
        conn: Connection,
        roleName: String,
        dbName: String,
    ) {
        val grants =
            listOf(
                """GRANT USAGE ON SCHEMA public TO "$roleName"""",
                """GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "$roleName"""",
                """GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "$roleName"""",
                """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "$roleName"""",
                """ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "$roleName"""",
            )

        try {
            grants.forEach { sql -> conn.createStatement().execute(sql) }
            logger.info { "Privileges on '$dbName' granted to '$roleName'" }
        } catch (e: SQLException) {
            logger.error { "Failed to grant privileges on '$dbName' to '$roleName': ${e.message}" }
            throw e
        }
    }

    private fun Connection.escapeString(value: String): String {
        val escaped = value.replace("'", "''")
        return "'$escaped'"
    }
}
