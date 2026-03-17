package com.superkooka.operator.postgres.reconcilier

import com.superkooka.operator.postgres.api.DatabaseClaim
import io.fabric8.kubernetes.api.model.Condition
import io.fabric8.kubernetes.api.model.HasMetadata
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

abstract class AbstractReconciler<T : HasMetadata> : Reconciler<T> {
    protected fun updateCondition(
        resource: DatabaseClaim,
        type: String,
        status: String,
        reason: String,
        message: String,
    ) {
        val conditions = resource.status.conditions
        val existing = conditions.find { it.type == type }

        if (existing == null || existing.status != status || existing.reason != reason || existing.message != message) {
            val now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
            val newCondition =
                Condition().apply {
                    this.type = type
                    this.status = status
                    this.reason = reason
                    this.message = message
                    this.lastTransitionTime = now
                }
            conditions.removeIf { it.type == type }
            conditions.add(newCondition)
        }
    }

    protected fun failWith(
        resource: DatabaseClaim,
        reason: String,
        message: String,
    ): UpdateControl<DatabaseClaim> {
        resource.status.phase = "Failed"
        resource.status.message = message
        updateCondition(resource, "Ready", "False", reason, message)
        return UpdateControl.patchStatus(resource)
    }
}
