package com.superkooka.operator.postgres.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.SQLException
import kotlin.use

private val logger = KotlinLogging.logger {}

class DatabaseProvisioner {
    fun ensureDatabase(
        connectionFactory: PostgresConnectionFactory,
        dbName: String,
    ) {
        dbName.validateIdentifier()

        try {
            connectionFactory.connect().use { conn ->
                if (!conn.databaseExists(dbName)) {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""CREATE DATABASE "$dbName"""")
                    }
                    logger.info { "Database '$dbName' created" }
                } else {
                    logger.info { "Database '$dbName' already exists, skipping" }
                }
            }
        } catch (e: SQLException) {
            throw ProvisioningException(
                ProvisioningError.DATABASE_CREATE_FAILED,
                "Failed to create database '$dbName'",
                e,
            )
        }
    }

    private fun Connection.databaseExists(dbName: String): Boolean =
        prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { stmt ->
            stmt.setString(1, dbName)
            stmt.executeQuery().next()
        }
}
