package com.superkooka.operator.postgres.api

import com.superkooka.operator.postgres.postgres.Permission
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
    var schemas: List<DatabaseSchema> = emptyList()
    var roles: List<DatabaseRole> = emptyList()
    var users: List<DatabaseUser> = emptyList()
}

class DatabaseSchema {
    lateinit var name: String
}

class DatabaseRole {
    lateinit var name: String
    var schemas: Map<String, RoleSchemaPermissions> = emptyMap()
}

class RoleSchemaPermissions {
    var permissions: List<Permission> = emptyList()
}

class DatabaseUser {
    lateinit var name: String
    lateinit var secretName: String
    var roles: List<String> = emptyList()
}

class DatabaseClaimStatus {
    var phase: String = "Pending"
    var message: String = ""
    var conditions: MutableList<Condition> = mutableListOf()
    var observedGeneration: Long? = null
}
