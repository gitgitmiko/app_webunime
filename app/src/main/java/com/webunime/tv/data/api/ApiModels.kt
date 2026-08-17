package com.webunime.tv.data.api

import com.webunime.tv.data.CatalogItem

data class AuthUser(
    val id: Int = 0,
    val email: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val createdAt: String? = null,
    val isActive: Boolean = true,
    val canInvite: Boolean = false,
    val isAdmin: Boolean = false,
) {
    fun displayLabel(): String =
        displayName?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }
            ?: email
            ?: "Akun"
}

data class CatalogPage(
    val collection: String = "",
    val page: Int = 1,
    val limit: Int = 12,
    val total: Int = 0,
    val items: List<CatalogItem> = emptyList(),
) {
    fun hasMore(): Boolean {
        val loaded = (page.coerceAtLeast(1) - 1) * limit.coerceAtLeast(1) + items.size
        return items.isNotEmpty() && loaded < total
    }
}

data class LibraryEntry(
    val collection: String = "",
    val slug: String = "",
    val title: String? = null,
    val thumbnail: String? = null,
    val episodeSlug: String? = null,
    val progressSeconds: Long = 0L,
    val lastWatchedAt: String? = null,
    val createdAt: String? = null,
) {
    fun toCatalogItem(type: String? = null): CatalogItem =
        CatalogItem(
            type = type,
            judul = title,
            thumbnail = thumbnail,
            slug = slug,
            catalog = collection,
            durasi = if (progressSeconds > 0) formatProgress(progressSeconds) else null,
            episode_source = episodeSlug,
        )

    private fun formatProgress(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "Lanjut %d:%02d".format(m, s)
    }
}
