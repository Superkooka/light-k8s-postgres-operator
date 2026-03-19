package com.superkooka.operator.postgres.postgres

enum class ProvisioningError {
    DATABASE_CREATE_FAILED,
    ROLE_CREATE_FAILED,
    ROLE_PASSWORD_UPDATE_FAILED,
    ROLE_PRIVILEGES_GRANT_FAILED,
    ROLE_PRIVILEGES_REVOKE_FAILED,
    SCHEMA_CREATE_FAILED,
    SCHEMA_PRIVILEGES_GRANT_FAILED,
    SCHEMA_PRIVILEGES_REVOKE_FAILED,
    DEFAULT_PRIVILEGES_GRANT_FAILED,
    DEFAULT_PRIVILEGES_REVOKE_FAILED,
}

class ProvisioningException(
    val error: ProvisioningError,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
