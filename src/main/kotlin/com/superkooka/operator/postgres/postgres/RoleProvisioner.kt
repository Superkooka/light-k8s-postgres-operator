package com.superkooka.operator.postgres.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.SQLException
import kotlin.use

private val logger = KotlinLogging.logger {}

class RoleProvisioner {
    fun ensureRole(
        connectionFactory: PostgresConnectionFactory,
        roleName: String,
        login: Boolean,
        password: String? = null,
    ) {
        try {
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
                    logger.info { "Role '$roleName' updated (password reset)" }
                } else {
                    conn.createStatement().execute(
                        """ALTER ROLE "${roleName.validateIdentifier()}" WITH $loginClause""",
                    )
                    logger.info { "Role '$roleName' exists, updated attributes (login=$login)" }
                }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_CREATE_FAILED,
                "Failed to ensure role '$roleName'",
                e,
            )
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
        try {
            connectionFactory.connect().use { conn ->
                conn.createStatement().execute(
                    """GRANT $privs ON DATABASE "${dbName.validateIdentifier()}" TO "${roleName.validateIdentifier()}"""",
                )
            }
            logger.info { "Granted $privs on database '$dbName' to role '$roleName'" }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_PRIVILEGES_GRANT_FAILED,
                "Failed to grant $privs on database '$dbName' to role '$roleName'",
                e,
            )
        }
    }

    fun revokeDatabasePermissions(
        connectionFactory: PostgresConnectionFactory,
        roleName: String,
        dbName: String,
        permissions: List<Permission>,
    ) {
        if (permissions.isEmpty()) return
        val privs = permissions.joinToString(", ")
        try {
            connectionFactory.connect().use { conn ->
                conn.createStatement().execute(
                    """REVOKE $privs ON DATABASE "${dbName.validateIdentifier()}" FROM "${roleName.validateIdentifier()}"""",
                )
            }
            logger.info { "Revoked $privs from database '$dbName' for role '$roleName'" }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_PRIVILEGES_REVOKE_FAILED,
                "Failed to revoke $privs from database '$dbName' for role '$roleName'",
                e,
            )
        }
    }

    fun grantRole(
        connectionFactory: PostgresConnectionFactory,
        parent: String,
        child: String,
    ) {
        try {
            connectionFactory.connect().use { conn ->
                conn.createStatement().execute(
                    """GRANT "${parent.validateIdentifier()}" TO "${child.validateIdentifier()}"""",
                )
            }
            logger.info { "Granted role '$parent' to '$child'" }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_PRIVILEGES_GRANT_FAILED,
                "Failed to grant role '$parent' to '$child'",
                e,
            )
        }
    }

    fun revokeRole(
        connectionFactory: PostgresConnectionFactory,
        parent: String,
        child: String,
    ) {
        try {
            connectionFactory.connect().use { conn ->
                conn.createStatement().execute(
                    """REVOKE "${parent.validateIdentifier()}" FROM "${child.validateIdentifier()}"""",
                )
            }
            logger.info { "Revoked role '$parent' from '$child'" }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.ROLE_PRIVILEGES_REVOKE_FAILED,
                "Failed to revoke role '$parent' from '$child'",
                e,
            )
        }
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
