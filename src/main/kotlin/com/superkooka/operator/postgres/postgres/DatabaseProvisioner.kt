package com.superkooka.operator.postgres.postgres

import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

class DatabaseProvisioner(
    private val connectionFactory: PostgresConnectionFactory,
) {
    fun ensureDatabase(dbName: String) {
        try {
            connectionFactory.connect().use { conn ->
                if (!this.databaseExists(dbName)) {
                    conn.prepareStatement("""CREATE DATABASE ?""").use { stmt ->
                        stmt.setString(1, dbName)
                        stmt.execute()
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

    fun databaseExists(dbName: String): Boolean =
        connectionFactory.connect().use { conn ->
            conn
                .prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
                .use { stmt ->
                    stmt.setString(1, dbName)
                    stmt.executeQuery().next()
                }
        }
}
