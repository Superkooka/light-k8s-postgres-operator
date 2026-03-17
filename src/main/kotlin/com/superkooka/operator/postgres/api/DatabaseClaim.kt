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
    lateinit var postgresRef: ResourceRef
    lateinit var credentialsRef: ResourceRef
    lateinit var database: String
    lateinit var owner: String
}

class ResourceRef {
    lateinit var name: String
    var namespace: String? = null
}

class DatabaseClaimStatus {
    var phase: String = "Pending"
    var message: String = ""
    var connectionSecret: String = ""
    var conditions: MutableList<Condition> = mutableListOf()
    var observedGeneration: Long? = null
}
