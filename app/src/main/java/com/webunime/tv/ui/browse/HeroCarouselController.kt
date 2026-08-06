package com.webunime.tv.ui.browse

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem

/**
 * Hero carousel ala idlix: backdrop + panel teks + dots.
 * Auto-rotate film unggulan saat idle; pause saat fokus kartu baris.
 */
class HeroCarouselController(
    private val context: Context,
    private val backdrop: ImageView,
    private val panel: View,
    private val badge: TextView,
    private val title: TextView,
    private val meta: TextView,
    private val synopsis: TextView,
    private val watch: Button,
    private val dots: LinearLayout,
    private val onWatch: (CatalogItem) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var featured: List<CatalogItem> = emptyList()
    private var index = 0
    private var current: CatalogItem? = null
    private var autoRotate = true
    private var lastBackdropUrl: String? = null
    private var bound = false

    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (!autoRotate || featured.size <= 1) return
            index = (index + 1) % featured.size
            showItem(featured[index], fromRotate = true)
            handler.postDelayed(this, ROTATE_MS)
        }
    }

    fun bind(activityRoot: View) {
        if (bound) return
        bound = true
        watch.setOnClickListener {
            val item = current ?: return@setOnClickListener
            onWatch(item)
        }
    }

    fun setFeatured(items: List<CatalogItem>) {
        featured = items
        index = 0
        rebuildDots()
        if (featured.isEmpty()) {
            panel.visibility = View.GONE
            stopRotate()
            return
        }
        panel.visibility = View.VISIBLE
        showItem(featured[0], fromRotate = true)
        if (autoRotate) startRotate()
    }

    /** Fokus kartu di baris: pause rotate, tampilkan item fokus. */
    fun onBrowseItemFocused(item: CatalogItem?) {
        if (item == null) {
            resumeRotate()
            return
        }
        pauseRotate()
        showItem(item, fromRotate = false)
    }

    fun pauseRotate() {
        autoRotate = false
        stopRotate()
        // Sembunyikan dots saat ikut fokus kartu (bukan mode carousel unggulan)
        dots.visibility = if (featured.size > 1) View.INVISIBLE else View.GONE
    }

    fun resumeRotate() {
        if (featured.isEmpty()) return
        autoRotate = true
        dots.visibility = if (featured.size > 1) View.VISIBLE else View.GONE
        val slug = current?.slug
        val i = featured.indexOfFirst { it.slug == slug && !slug.isNullOrBlank() }
        if (i >= 0) {
            index = i
        } else {
            // Kembali dari Lanjutkan / judul non-featured → tampilkan slide unggulan lagi
            showItem(featured[index.coerceIn(0, featured.lastIndex)], fromRotate = true)
        }
        updateDots()
        startRotate()
    }

    fun release() {
        stopRotate()
        runCatching { Glide.with(backdrop).clear(backdrop) }
        current = null
        featured = emptyList()
    }

    private fun startRotate() {
        stopRotate()
        if (!autoRotate || featured.size <= 1) return
        handler.postDelayed(rotateRunnable, ROTATE_MS)
    }

    private fun stopRotate() {
        handler.removeCallbacks(rotateRunnable)
    }

    private fun showItem(item: CatalogItem, fromRotate: Boolean) {
        current = item
        badge.text = badgeLabel(item)
        title.text = item.displayTitle()
        meta.text = buildMeta(item)
        val syn = item.sinopsis?.trim().orEmpty()
        synopsis.text = syn
        synopsis.visibility = if (syn.isBlank()) View.GONE else View.VISIBLE
        watch.visibility =
            if (item.slug.isNullOrBlank() && item.anime_slug.isNullOrBlank()) View.GONE
            else View.VISIBLE

        val url = item.thumbnail_landscape?.takeIf { it.isNotBlank() }
            ?: item.thumbnail?.takeIf { it.isNotBlank() }
        loadBackdrop(url)

        if (fromRotate) updateDots()
    }

    private fun loadBackdrop(url: String?) {
        if (url.isNullOrBlank()) {
            lastBackdropUrl = null
            backdrop.setImageDrawable(
                ColorDrawable(ContextCompat.getColor(context, R.color.wu_bg))
            )
            return
        }
        if (url == lastBackdropUrl) return
        lastBackdropUrl = url
        Glide.with(backdrop)
            .load(url)
            .centerCrop()
            .placeholder(ColorDrawable(ContextCompat.getColor(context, R.color.wu_bg)))
            .error(ColorDrawable(ContextCompat.getColor(context, R.color.wu_bg)))
            .into(backdrop)
    }

    private fun rebuildDots() {
        dots.removeAllViews()
        if (featured.size <= 1) {
            dots.visibility = View.GONE
            return
        }
        dots.visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        val size = (8 * density).toInt()
        val gap = (6 * density).toInt()
        for (i in featured.indices) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    if (i > 0) it.marginStart = gap
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66FFFFFF)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            dots.addView(dot)
        }
        updateDots()
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val bg = dot.background as? GradientDrawable ?: continue
            if (i == index) {
                bg.setColor(ContextCompat.getColor(context, R.color.wu_accent))
                val density = context.resources.displayMetrics.density
                val lp = dot.layoutParams as LinearLayout.LayoutParams
                lp.width = (22 * density).toInt()
                lp.height = (8 * density).toInt()
                dot.layoutParams = lp
                bg.cornerRadius = 4 * density
            } else {
                bg.setColor(0x66FFFFFF)
                val density = context.resources.displayMetrics.density
                val lp = dot.layoutParams as LinearLayout.LayoutParams
                lp.width = (8 * density).toInt()
                lp.height = (8 * density).toInt()
                dot.layoutParams = lp
                bg.shape = GradientDrawable.OVAL
            }
        }
    }

    private fun badgeLabel(item: CatalogItem): String {
        val t = item.type?.lowercase().orEmpty()
        return when {
            t == BrowseFragment.TYPE_CONTINUE ->
                context.getString(R.string.hero_badge_continue)
            t.contains("anime") ->
                context.getString(R.string.hero_badge_anime)
            t.contains("series") || (!item.episodes.isNullOrEmpty() && t != "anime-movie") ->
                context.getString(R.string.hero_badge_series)
            t.contains("horror") ||
                item.genre.orEmpty().any { it.equals("Horror", ignoreCase = true) } ->
                context.getString(R.string.hero_badge_horror)
            else -> context.getString(R.string.hero_badge_movie)
        }
    }

    private fun buildMeta(item: CatalogItem): String {
        val parts = mutableListOf<String>()
        item.rating?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
        item.tahun?.takeIf { it.isNotBlank() }?.let { parts += it }
        item.durasi?.takeIf { it.isNotBlank() }?.let { parts += it }
        val genres = item.genre.orEmpty().take(3).joinToString(", ")
        if (genres.isNotBlank()) parts += genres
        return parts.joinToString("  ·  ")
    }

    companion object {
        private const val ROTATE_MS = 5000L

        fun attach(
            activity: android.app.Activity,
            onWatch: (CatalogItem) -> Unit,
        ): HeroCarouselController? {
            val backdrop = activity.findViewById<ImageView>(R.id.browseBackdrop) ?: return null
            val panel = activity.findViewById<View>(R.id.browseHeroPanel) ?: return null
            val badge = activity.findViewById<TextView>(R.id.browseHeroBadge) ?: return null
            val title = activity.findViewById<TextView>(R.id.browseHeroTitle) ?: return null
            val meta = activity.findViewById<TextView>(R.id.browseHeroMeta) ?: return null
            val synopsis = activity.findViewById<TextView>(R.id.browseHeroSynopsis) ?: return null
            val watch = activity.findViewById<Button>(R.id.browseHeroWatch) ?: return null
            val dots = activity.findViewById<LinearLayout>(R.id.browseHeroDots) ?: return null
            return HeroCarouselController(
                context = activity,
                backdrop = backdrop,
                panel = panel,
                badge = badge,
                title = title,
                meta = meta,
                synopsis = synopsis,
                watch = watch,
                dots = dots,
                onWatch = onWatch,
            ).also { it.bind(panel) }
        }
    }
}
