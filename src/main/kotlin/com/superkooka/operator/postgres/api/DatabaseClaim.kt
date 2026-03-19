package com.superkooka.operator.postgres.api

import io.fabric8.kubernetes.api.model.Condition
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

@Group("postgres.operator.superkooka.com")
@Version("v1beta1")
class DatabaseClaim :
    CustomResource<DatabaseClaimSpec, DatabaseClaimStatus>(),
    Namespaced

class DatabaseClaimSpec {
    lateinit var instanceRef: ResourceRef
    lateinit var database: String
    var users: List<DatabaseUser> = emptyList()
}

class DatabaseUser {
    lateinit var name: String
    lateinit var secretName: String
    var permissions: List<Permission> = Permission.ALL
}

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
    }
}

class DatabaseClaimStatus {
    var phase: String = "Pending"
    var message: String = ""
    var conditions: MutableList<Condition> = mutableListOf()
    var observedGeneration: Long? = null
}
