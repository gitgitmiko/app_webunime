package com.webunime.tv.ui.browse

import android.graphics.Color

/**
 * Warna badge genre / negara untuk hero carousel.
 */
object HeroBadgeStyles {

    fun genreColor(genre: String): Int {
        val g = genre.trim().lowercase()
        return when {
            g.contains("romance") || g.contains("romantis") || g == "love" ->
                Color.argb(0xE6, 0xE9, 0x1E, 0x63)
            g.contains("horror") || g.contains("horor") ->
                Color.argb(0xE6, 0xB7, 0x1C, 0x1C)
            g.contains("thriller") || g.contains("suspense") ->
                Color.argb(0xE6, 0x4A, 0x14, 0x8C)
            g.contains("action") || g.contains("aksi") ->
                Color.argb(0xE6, 0xE6, 0x51, 0x00)
            g.contains("comedy") || g.contains("komedi") ->
                Color.argb(0xE6, 0xF9, 0xA8, 0x25)
            g.contains("drama") ->
                Color.argb(0xE6, 0x5E, 0x35, 0xB1)
            g.contains("sci") || g.contains("science") || g.contains("fantasi") || g.contains("fantasy") ->
                Color.argb(0xE6, 0x00, 0x89, 0x7B)
            g.contains("adventure") || g.contains("petualangan") ->
                Color.argb(0xE6, 0x2E, 0x7D, 0x32)
            g.contains("family") || g.contains("keluarga") || g.contains("kids") || g.contains("anak") ->
                Color.argb(0xE6, 0x02, 0x88, 0xD1)
            g.contains("animation") || g.contains("animasi") || g.contains("anime") ->
                Color.argb(0xE6, 0x00, 0x96, 0x88)
            g.contains("crime") || g.contains("kriminal") || g.contains("gangster") ->
                Color.argb(0xE6, 0x37, 0x47, 0x4F)
            g.contains("mystery") || g.contains("misteri") ->
                Color.argb(0xE6, 0x28, 0x35, 0x93)
            g.contains("war") || g.contains("perang") ->
                Color.argb(0xE6, 0x55, 0x6B, 0x2F)
            g.contains("history") || g.contains("sejarah") || g.contains("period") ->
                Color.argb(0xE6, 0x6D, 0x4C, 0x41)
            g.contains("sport") || g.contains("olahraga") ->
                Color.argb(0xE6, 0x15, 0x65, 0xC0)
            g.contains("music") || g.contains("musik") || g.contains("musical") ->
                Color.argb(0xE6, 0xC2, 0x18, 0x5B)
            g.contains("western") ->
                Color.argb(0xE6, 0x8D, 0x6E, 0x63)
            g.contains("documentary") || g.contains("dokumenter") ->
                Color.argb(0xE6, 0x5D, 0x40, 0x37)
            g.contains("biography") || g.contains("biografi") ->
                Color.argb(0xE6, 0x45, 0x5A, 0x64)
            else -> Color.argb(0xE6, 0x42, 0x42, 0x42)
        }
    }

    /** Teks badge negara: emoji bendera + nama singkat. */
    fun countryBadgeText(negaraRaw: String): String {
        val name = firstCountry(negaraRaw) ?: return negaraRaw.trim()
        val flag = countryFlag(name)
        return if (flag != null) "$flag $name" else name
    }

    fun countryColor(negaraRaw: String): Int {
        val name = firstCountry(negaraRaw)?.lowercase().orEmpty()
        return when {
            name.contains("indonesia") -> Color.argb(0xE6, 0xCE, 0x11, 0x26)
            name.contains("japan") || name.contains("jepang") -> Color.argb(0xE6, 0xBC, 0x00, 0x2D)
            name.contains("korea") || name.contains("south korea") || name.contains("korut") ->
                Color.argb(0xE6, 0x00, 0x47, 0xA0)
            name.contains("china") || name.contains("tiongkok") || name.contains("hong kong") ||
                name.contains("taiwan") -> Color.argb(0xE6, 0xDE, 0x29, 0x10)
            name.contains("united states") || name.contains("usa") || name.contains("america") ||
                name == "us" || name.contains("amerika") -> Color.argb(0xE6, 0x3C, 0x3B, 0x6E)
            name.contains("united kingdom") || name.contains("england") || name.contains("british") ||
                name.contains("inggris") || name == "uk" -> Color.argb(0xE6, 0x01, 0x22, 0x69)
            name.contains("france") || name.contains("prancis") -> Color.argb(0xE6, 0x00, 0x55, 0xA4)
            name.contains("germany") || name.contains("jerman") -> Color.argb(0xE6, 0xDD, 0x00, 0x00)
            name.contains("italy") || name.contains("italia") -> Color.argb(0xE6, 0x00, 0x92, 0x46)
            name.contains("spain") || name.contains("spanyol") -> Color.argb(0xE6, 0xAA, 0x15, 0x1B)
            name.contains("india") -> Color.argb(0xE6, 0xFF, 0x99, 0x33)
            name.contains("thailand") -> Color.argb(0xE6, 0xA5, 0x19, 0x31)
            name.contains("philippines") || name.contains("filipina") -> Color.argb(0xE6, 0x00, 0x38, 0xA8)
            name.contains("vietnam") -> Color.argb(0xE6, 0xDA, 0x25, 0x1D)
            name.contains("malaysia") -> Color.argb(0xE6, 0xCC, 0x00, 0x01)
            name.contains("singapore") || name.contains("singapura") -> Color.argb(0xE6, 0xEF, 0x33, 0x4D)
            name.contains("australia") -> Color.argb(0xE6, 0x00, 0x00, 0x8B)
            name.contains("canada") || name.contains("kanada") -> Color.argb(0xE6, 0xFF, 0x00, 0x00)
            name.contains("brazil") || name.contains("brasil") -> Color.argb(0xE6, 0x00, 0x96, 0x39)
            name.contains("mexico") || name.contains("meksiko") -> Color.argb(0xE6, 0x00, 0x67, 0x47)
            name.contains("russia") || name.contains("rusia") -> Color.argb(0xE6, 0xD5, 0x2B, 0x1E)
            name.contains("turkey") || name.contains("turki") -> Color.argb(0xE6, 0xE3, 0x0A, 0x17)
            else -> Color.argb(0xE6, 0x45, 0x45, 0x45)
        }
    }

    fun firstCountry(negaraRaw: String): String? =
        negaraRaw
            .split(",", "/", "|", "&", " dan ")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }

    fun countryFlag(negara: String): String? {
        val n = negara.lowercase()
        return when {
            n.contains("indonesia") -> "🇮🇩"
            n.contains("japan") || n.contains("jepang") -> "🇯🇵"
            n.contains("south korea") || n.contains("korea selatan") ||
                (n.contains("korea") && !n.contains("utara") && !n.contains("north")) -> "🇰🇷"
            n.contains("north korea") || n.contains("korea utara") -> "🇰🇵"
            n.contains("china") || n.contains("tiongkok") -> "🇨🇳"
            n.contains("hong kong") -> "🇭🇰"
            n.contains("taiwan") -> "🇹🇼"
            n.contains("united states") || n.contains("usa") || n.contains("america") ||
                n == "us" || n.contains("amerika serikat") || n.contains("amerika") -> "🇺🇸"
            n.contains("united kingdom") || n.contains("england") || n.contains("british") ||
                n.contains("inggris") || n == "uk" -> "🇬🇧"
            n.contains("france") || n.contains("prancis") -> "🇫🇷"
            n.contains("germany") || n.contains("jerman") -> "🇩🇪"
            n.contains("italy") || n.contains("italia") -> "🇮🇹"
            n.contains("spain") || n.contains("spanyol") -> "🇪🇸"
            n.contains("india") -> "🇮🇳"
            n.contains("thailand") -> "🇹🇭"
            n.contains("philippines") || n.contains("filipina") -> "🇵🇭"
            n.contains("vietnam") -> "🇻🇳"
            n.contains("malaysia") -> "🇲🇾"
            n.contains("singapore") || n.contains("singapura") -> "🇸🇬"
            n.contains("australia") -> "🇦🇺"
            n.contains("canada") || n.contains("kanada") -> "🇨🇦"
            n.contains("brazil") || n.contains("brasil") -> "🇧🇷"
            n.contains("mexico") || n.contains("meksiko") -> "🇲🇽"
            n.contains("russia") || n.contains("rusia") -> "🇷🇺"
            n.contains("turkey") || n.contains("turki") -> "🇹🇷"
            n.contains("netherlands") || n.contains("belanda") -> "🇳🇱"
            n.contains("sweden") || n.contains("swedia") -> "🇸🇪"
            n.contains("norway") || n.contains("norwegia") -> "🇳🇴"
            n.contains("denmark") || n.contains("denmark") -> "🇩🇰"
            n.contains("finland") || n.contains("finlandia") -> "🇫🇮"
            n.contains("poland") || n.contains("polandia") -> "🇵🇱"
            n.contains("argentina") -> "🇦🇷"
            n.contains("egypt") || n.contains("mesir") -> "🇪🇬"
            n.contains("saudi") -> "🇸🇦"
            n.contains("uae") || n.contains("emirates") || n.contains("arab") -> "🇦🇪"
            else -> null
        }
    }
}
