package com.superkooka.operator.postgres.postgres

enum class ProvisioningError {
    DATABASE_CREATE_FAILED,
    ROLE_CREATE_FAILED,
    ROLE_PASSWORD_UPDATE_FAILED,
    ROLE_PRIVILEGES_GRANT_FAILED,
}

class ProvisioningException(
    val error: ProvisioningError,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
