@file:Suppress("ktlint:standard:filename")

package com.superkooka.operator.postgres.api

class ResourceRef {
    lateinit var name: String
    var namespace: String? = null
}
