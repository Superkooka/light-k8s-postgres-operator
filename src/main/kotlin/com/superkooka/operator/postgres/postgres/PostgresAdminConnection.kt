package com.superkooka.operator.postgres.postgres

import java.sql.Connection
import java.sql.DriverManager

data class PostgresAdminCredentials(
    val host: String,
    val port: Int,
    val adminUser: String,
    val adminPassword: String,
)

class PostgresConnectionFactory(
    val credentials: PostgresAdminCredentials,
) {
    fun connect(database: String = "postgres"): Connection {
        val url = "jdbc:postgresql://${credentials.host}:${credentials.port}/$database"
        return DriverManager.getConnection(url, credentials.adminUser, credentials.adminPassword).also {
            it.autoCommit = true
        }
    }
}

fun String.validateIdentifier(): String {
    require(this.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) { "Invalid identifier: $this" }
    return this
}
