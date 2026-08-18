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
    val episodeNum: Int? = null,
    val progressSeconds: Long = 0L,
    val lastWatchedAt: String? = null,
    val createdAt: String? = null,
) {
    fun resolvedEpisodeNum(): Int? = parseEpisodeNum(episodeNum, episodeSlug)

    fun toCatalogItem(type: String? = null): CatalogItem =
        CatalogItem(
            type = type,
            judul = title,
            thumbnail = thumbnail,
            slug = slug,
            catalog = collection,
            episode = resolvedEpisodeNum(),
            durasi = if (progressSeconds > 0) formatProgress(progressSeconds) else null,
            episode_source = episodeSlug,
        )

    private fun formatProgress(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "Lanjut %d:%02d".format(m, s)
    }

    companion object {
        fun parseEpisodeNum(episodeNum: Int?, episodeSlug: String?): Int? {
            if (episodeNum != null && episodeNum > 0) return episodeNum
            val m = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(episodeSlug.orEmpty())
            return m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}

data class WatchedEpisode(
    val episodeSlug: String? = null,
    val episodeNum: Int? = null,
    val watchedAt: String? = null,
) {
    fun matches(episodeSlug: String?, episodeNum: Int?): Boolean {
        if (!episodeSlug.isNullOrBlank() && this.episodeSlug.equals(episodeSlug, true)) return true
        val n = episodeNum?.takeIf { it > 0 } ?: return false
        return this.episodeNum == n ||
            LibraryEntry.parseEpisodeNum(this.episodeNum, this.episodeSlug) == n
    }
}
