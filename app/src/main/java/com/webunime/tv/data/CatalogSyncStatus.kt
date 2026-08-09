package com.webunime.tv.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CatalogSyncStatus(
    val state: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val runId: Long? = null,
    val message: String? = null,
) {
    fun normalizedState(): String = state?.trim()?.lowercase().orEmpty()

    fun isRunning(): Boolean = normalizedState() == "running"

    fun isFailed(): Boolean = normalizedState() == "failed"
}
