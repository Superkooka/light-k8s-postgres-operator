package com.superkooka.operator.postgres.api

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

@Group("postgres.operator.superkooka.com")
@Version("v1beta1")
class PostgresInstance :
    CustomResource<PostgresInstanceSpec, PostgresInstanceStatus>(),
    Namespaced

class PostgresInstanceSpec {
    lateinit var postgresServiceRef: ResourceRef
    lateinit var credentialsRef: ResourceRef
}

class PostgresInstanceStatus {
    var phase: String = "Pending"
    var message: String = ""
    var conditions: MutableList<io.fabric8.kubernetes.api.model.Condition> = mutableListOf()
    var observedGeneration: Long? = null
}
