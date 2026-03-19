package com.superkooka.operator.postgres.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import kotlin.use

private val logger = KotlinLogging.logger {}

class RoleProvisioner {
    fun ensureRole(
        connectionFactory: PostgresConnectionFactory,
        roleName: String,
        login: Boolean,
        password: String? = null,
    ) {
        connectionFactory.connect().use { conn ->
            val exists = roleExists(conn, roleName)

            val loginClause = if (login) "LOGIN" else "NOLOGIN"
            val passwordClause =
                if (login && password != null) {
                    "PASSWORD ${quoteLiteral(conn, password)}"
                } else {
                    ""
                }

            if (!exists) {
                conn.createStatement().execute(
                    """CREATE ROLE "${roleName.validateIdentifier()}" $loginClause $passwordClause""",
                )
                logger.info { "Role '$roleName' created (login=$login)" }
            } else if (login && password != null) {
                conn.createStatement().execute(
                    """ALTER ROLE "${roleName.validateIdentifier()}" WITH PASSWORD ${quoteLiteral(conn, password)} $loginClause""",
                )
                logger.info { "Role '$roleName' updated" }
            } else {
                conn.createStatement().execute(
                    """ALTER ROLE "${roleName.validateIdentifier()}" WITH $loginClause""",
                )
                logger.info { "Role '$roleName' exists, updated attributes" }
            }
        }
    }

    fun grantDatabasePermissions(
        connectionFactory: PostgresConnectionFactory,
        roleName: String,
        dbName: String,
        permissions: List<Permission>,
    ) {
        if (permissions.isEmpty()) return
        val privs = permissions.joinToString(", ")
        connectionFactory.connect().use { conn ->
            conn.createStatement().execute(
                """GRANT $privs ON DATABASE "${dbName.validateIdentifier()}" TO "${roleName.validateIdentifier()}"""",
            )
        }
        logger.info { "Granted $privs on database '$dbName' to role '$roleName'" }
    }

    fun grantRole(
        connectionFactory: PostgresConnectionFactory,
        parent: String,
        child: String,
    ) {
        connectionFactory.connect().use { conn ->
            conn.createStatement().execute(
                """GRANT "${parent.validateIdentifier()}" TO "${child.validateIdentifier()}"""",
            )
        }
        logger.info { "Granted role '$parent' to '$child'" }
    }

    fun roleExists(
        conn: Connection,
        roleName: String,
    ): Boolean =
        conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use {
            it.setString(1, roleName)
            it.executeQuery().next()
        }

    private fun quoteLiteral(
        conn: Connection,
        value: String,
    ): String =
        conn.prepareStatement("SELECT quote_literal(?)").use {
            it.setString(1, value)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
}
