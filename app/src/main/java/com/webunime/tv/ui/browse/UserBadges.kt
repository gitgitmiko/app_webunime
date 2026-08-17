package com.webunime.tv.ui.browse

import android.graphics.Color
import kotlin.random.Random

object UserBadges {
    private val colors = listOf(
        Color.parseColor("#E50914"),
        Color.parseColor("#E5A000"),
        Color.parseColor("#2E7D32"),
        Color.parseColor("#1565C0"),
        Color.parseColor("#6A1B9A"),
        Color.parseColor("#00838F"),
        Color.parseColor("#C2185B"),
        Color.parseColor("#EF6C00"),
        Color.parseColor("#3949AB"),
        Color.parseColor("#00897B"),
    )

    fun randomColor(): Int = colors.random(Random.Default)
}
