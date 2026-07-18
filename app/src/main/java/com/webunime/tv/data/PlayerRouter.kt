package com.webunime.tv.data

object PlayerRouter {

    private val filmPrefer = listOf("turbovip", "cast", "hydrax")

    fun preferredPlayers(item: CatalogItem, episode: Episode? = null): List<PlayerServer> {
        val raw = when {
            episode?.players?.isNotEmpty() == true -> episode.players
            item.players?.isNotEmpty() == true -> item.players
            else -> emptyList()
        }.orEmpty().filter { !it.url.isNullOrBlank() }

        if (raw.isEmpty()) return emptyList()

        val isAnime = item.type == "anime" || item.type == "anime-movie" ||
            item.anime_slug != null ||
            raw.any { p ->
                val u = (p.url ?: "").lowercase()
                u.contains("wibufile") || u.contains("blogger.com") ||
                    u.contains("filedon") || u.contains("mega.nz") ||
                    u.contains("pixeldrain")
            }

        if (isAnime) {
            return rankAnime(raw)
        }

        val ranked = filmPrefer.mapNotNull { key ->
            raw.firstOrNull { p ->
                val s = (p.server ?: "").lowercase()
                val l = (p.label ?: "").lowercase()
                s.contains(key) || l.contains(key)
            }
        }
        val rest = raw.filter { p -> ranked.none { it.url == p.url } }
            .filter { (it.server ?: "").lowercase() != "p2p" }
        val p2p = raw.filter { (it.server ?: "").lowercase() == "p2p" }
        return (ranked + rest + p2p).distinctBy { it.url }
    }

    private fun rankAnime(raw: List<PlayerServer>): List<PlayerServer> {
        fun score(p: PlayerServer): Int {
            val u = (p.url ?: "").lowercase()
            val l = (p.label ?: "").lowercase()
            val s = (p.server ?: "").lowercase()
            // User: Blogspot & Mega paling lancar; Wibufile/Premium sering buffering
            return when {
                u.contains("blogger.com") || s.contains("blogspot") || l.contains("blogspot") -> 0
                u.contains("mega.nz") && (l.contains("720") || l.contains("480")) -> 5
                u.contains("mega.nz") -> 8
                // Nakama via Pixeldrain API
                u.contains("pixeldrain") && (l.contains("720") || l.contains("480")) -> 15
                u.contains("pixeldrain") || s.contains("nakama") -> 18
                // Wibufile/Premium MP4 — cadangan
                u.contains("wibufile.com/video") && (u.contains("mp4hd") || l.contains("720")) -> 25
                u.contains("wibufile.com/video") || (u.contains(".mp4") && (u.contains("wibufile") || s.contains("premium"))) -> 28
                u.contains("api.wibufile.com/embed") || u.contains("login.wibufile.com") -> 35
                // Pucuk/VIP Filedon — sering R2 besar / MKV
                u.contains("filedon") || s.contains("pucuk") || s.contains("vip") || l.contains("vip") -> 45
                else -> 50
            }
        }
        return raw.sortedBy { score(it) }.distinctBy { it.url }
    }

    fun pickDefault(item: CatalogItem, episode: Episode? = null): PlayerServer? =
        preferredPlayers(item, episode).firstOrNull()

    fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("playeriframe") || u.contains("abyssplayer") || u.contains("gn1r5n") ||
            u.contains("turbo") || u.contains("emturbovid") || u.contains("blogger.com") ||
            u.contains("mega.nz") || u.contains("filedon.co/embed") ||
            u.contains("api.wibufile.com/embed") || u.contains("login.wibufile.com") ||
            (u.contains("pixeldrain.com/u/") && !u.contains("/api/file/"))
        ) {
            return false
        }
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".webm") ||
            u.contains("wibufile.com/video") ||
            u.contains("pixeldrain.com/api/file/") ||
            (u.contains("r2.cloudflarestorage") && (
                u.contains(".mp4") || u.contains("video") || u.contains(".m3u8") ||
                    u.contains("X-Amz-Signature") || u.contains("x-amz-signature")
                ))
    }

    fun useExoPlayer(url: String): Boolean = isDirectMedia(url)

    /**
     * Referer untuk host.
     * R2 signed URL: JANGAN kirim Referer — bisa memicu InvalidArgument Authorization.
     */
    fun refererFor(url: String): String? {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
        return when {
            host.contains("r2.cloudflarestorage") -> null
            host.contains("pixeldrain") -> "https://pixeldrain.com/"
            host.contains("wibufile") -> "https://api.wibufile.com/"
            host.contains("filedon") -> "https://filedon.co/"
            host.contains("blogger") || host.contains("google") -> "https://www.blogger.com/"
            host.contains("mega.nz") -> "https://mega.nz/"
            else -> null
        }
    }
}
