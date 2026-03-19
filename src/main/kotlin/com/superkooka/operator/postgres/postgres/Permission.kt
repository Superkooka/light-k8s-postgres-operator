package com.superkooka.operator.postgres.postgres

enum class Permission {
    CONNECT,
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    CREATE,
    TRUNCATE,
    REFERENCES,
    TRIGGER,
    ;

    companion object {
        val ALL = listOf(CONNECT, SELECT, INSERT, UPDATE, DELETE, CREATE, TRUNCATE, REFERENCES, TRIGGER)
        val READONLY = listOf(CONNECT, SELECT)
        val DATABASE_ALL = listOf(CONNECT, CREATE)
    }

    fun toTablePrivilege(): String? =
        when (this) {
            SELECT -> "SELECT"
            INSERT -> "INSERT"
            UPDATE -> "UPDATE"
            DELETE -> "DELETE"
            TRUNCATE -> "TRUNCATE"
            REFERENCES -> "REFERENCES"
            TRIGGER -> "TRIGGER"
            else -> null
        }
}
