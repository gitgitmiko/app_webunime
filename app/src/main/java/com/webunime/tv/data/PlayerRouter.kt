package com.webunime.tv.data

object PlayerRouter {

    /** Film / series / horor: Hydrax → TurboVIP → Cast → P2P (terakhir). */
    private val filmPrefer = listOf("hydrax", "turbovip", "cast")

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
            raw.firstOrNull { p -> matchesFilmKey(p, key) }
        }
        val rest = raw.filter { p ->
            ranked.none { it.url == p.url } && !isP2p(p)
        }
        val p2p = raw.filter { isP2p(it) }
        return (ranked + rest + p2p).distinctBy { it.url }
    }

    private fun matchesFilmKey(p: PlayerServer, key: String): Boolean {
        val s = (p.server ?: "").lowercase()
        val l = (p.label ?: "").lowercase()
        val u = (p.url ?: "").lowercase()
        return s.contains(key) || l.contains(key) || u.contains(key) ||
            (key == "hydrax" && (u.contains("abyss") || u.contains("gn1r5n")))
    }

    private fun isP2p(p: PlayerServer): Boolean {
        val s = (p.server ?: "").lowercase()
        val l = (p.label ?: "").lowercase()
        val u = (p.url ?: "").lowercase()
        return s.contains("p2p") || l.contains("p2p") || isP2pUrl(u)
    }

    /** URL player P2P (playcdn / p2pplay / wrapper /iframe/p2p/). */
    fun isP2pUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("p2pplay") ||
            u.contains("playcdn.de") ||
            u.contains("/iframe/p2p/") ||
            u.contains("/iframe/p2p?")
    }

    /**
     * Anime: Mega 1080 → 720 → 480 → Wibufile 1080 → 720 → 480 → Blogspot → lainnya.
     */
    private fun rankAnime(raw: List<PlayerServer>): List<PlayerServer> {
        fun score(p: PlayerServer): Int {
            val u = (p.url ?: "").lowercase()
            val l = (p.label ?: "").lowercase()
            val s = (p.server ?: "").lowercase()
            val res = resolutionRank(l, u)

            val isMega = u.contains("mega.nz") || s.contains("mega") || l.contains("mega")
            val isWibu = u.contains("wibufile") || s.contains("wibu") || s.contains("premium") ||
                l.contains("wibufile") || l.contains("premium")
            val isBlog = u.contains("blogger.com") || s.contains("blogspot") || l.contains("blogspot") ||
                l.contains("blogger")

            return when {
                isMega -> 10 + res
                isWibu && (u.contains("wibufile.com/video") || u.contains(".mp4")) -> 40 + res
                isWibu -> 50 + res
                isBlog -> 70
                u.contains("pixeldrain") || s.contains("nakama") -> 80 + res
                u.contains("filedon") || s.contains("pucuk") || s.contains("vip") -> 90
                else -> 100
            }
        }
        return raw.sortedBy { score(it) }.distinctBy { it.url }
    }

    /** 0=1080/HD, 1=720, 2=480, 3=lain. */
    private fun resolutionRank(label: String, url: String): Int {
        val t = "$label $url"
        return when {
            t.contains("1080") || t.contains("mp4hd") || Regex("\\bhd\\b").containsMatchIn(t) -> 0
            t.contains("720") -> 1
            t.contains("480") || t.contains("360") -> 2
            else -> 3
        }
    }

    fun pickDefault(item: CatalogItem, episode: Episode? = null): PlayerServer? =
        preferredPlayers(item, episode).firstOrNull()

    fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        if (EmbedResolver.isWrapperEmbed(url) ||
            u.contains("abyssplayer") || u.contains("gn1r5n") ||
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
            host.contains("p2pplay") -> "https://kconaz.com/"
            host.contains("playcdn") -> "https://videonode.de/"
            else -> null
        }
    }
}
