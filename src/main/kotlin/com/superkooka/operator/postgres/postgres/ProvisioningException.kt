package com.superkooka.operator.postgres.postgres

enum class ProvisioningError {
    DATABASE_CREATE_FAILED,
}

class ProvisioningException(
    val error: ProvisioningError,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
