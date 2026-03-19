package com.superkooka.operator.postgres.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection

private val logger = KotlinLogging.logger {}

class SchemaProvisioner {
    fun ensureSchema(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
        schema: String,
        owner: String,
    ) {
        connectionFactory.connect(dbName).use { conn ->
            conn.createStatement().execute(
                """CREATE SCHEMA IF NOT EXISTS "${schema.validateIdentifier()}" AUTHORIZATION "${owner.validateIdentifier()}"""",
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
        connectionFactory.connect(dbName).use { conn ->

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

        connectionFactory.connect(dbName).use { conn ->
            exec(
                conn,
                """
                ALTER DEFAULT PRIVILEGES FOR ROLE "${owner.validateIdentifier()}"
                IN SCHEMA "${schema.validateIdentifier()}"
                GRANT $tablePrivileges ON TABLES TO "${role.validateIdentifier()}"
                """.trimIndent(),
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
