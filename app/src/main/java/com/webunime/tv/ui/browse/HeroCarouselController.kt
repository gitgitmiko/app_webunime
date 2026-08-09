package com.webunime.tv.ui.browse

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem

/**
 * Hero carousel: backdrop landscape + teks di baris Leanback.
 * Geser manual ←/→; OK buka detail. Tanpa trailer video.
 */
class HeroCarouselController(
    private val context: Context,
    private val backdrop: ImageView,
    private val onWatch: (CatalogItem) -> Unit,
) {
    private var featured: List<CatalogItem> = emptyList()
    private var index = 0
    private var current: CatalogItem? = null
    private var lastBackdropUrl: String? = null
    private var featuredMode = true

    private var panel: View? = null
    private var badge: TextView? = null
    private var title: TextView? = null
    private var meta: TextView? = null
    private var synopsis: TextView? = null
    private var hint: TextView? = null
    private var dots: LinearLayout? = null

    private val keyListener = View.OnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPrev()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showNext()
                true
            }
            else -> false
        }
    }

    fun bindHeroView(view: View) {
        if (panel === view) {
            refreshBoundPanel()
            return
        }
        unbindHeroView(panel)
        panel = view
        badge = view.findViewById(R.id.browseHeroBadge)
        title = view.findViewById(R.id.browseHeroTitle)
        meta = view.findViewById(R.id.browseHeroMeta)
        synopsis = view.findViewById(R.id.browseHeroSynopsis)
        hint = view.findViewById(R.id.browseHeroHint)
        dots = view.findViewById(R.id.browseHeroDots)

        view.setOnKeyListener(keyListener)
        view.setOnClickListener {
            val item = current ?: return@setOnClickListener
            onWatch(item)
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showFeaturedMode()
                hint?.visibility = if (featured.size > 1) View.VISIBLE else View.GONE
            }
        }

        refreshBoundPanel()
    }

    fun unbindHeroView(view: View?) {
        if (view == null) return
        if (panel !== view) return
        view.setOnKeyListener(null)
        view.setOnClickListener(null)
        view.onFocusChangeListener = null
        panel = null
        badge = null
        title = null
        meta = null
        synopsis = null
        hint = null
        dots = null
    }

    fun setFeatured(items: List<CatalogItem>) {
        featured = items
        index = 0
        featuredMode = true
        if (featured.isEmpty()) {
            current = null
            return
        }
        showItem(featured[0], updateDots = true)
        updateChrome()
    }

    fun currentItem(): CatalogItem? = current

    fun onBrowseItemFocused(item: CatalogItem?) {
        if (item == null) {
            showFeaturedMode()
            return
        }
        featuredMode = false
        updateChrome()
        showItem(item, updateDots = false)
    }

    fun showFeaturedMode() {
        if (featured.isEmpty()) return
        featuredMode = true
        updateChrome()
        val slug = current?.slug
        val i = featured.indexOfFirst { it.slug == slug && !slug.isNullOrBlank() }
        if (i >= 0) {
            index = i
            updateDots()
            showItem(featured[i], updateDots = true)
        } else {
            showItem(featured[index.coerceIn(0, featured.lastIndex)], updateDots = true)
        }
    }

    fun pauseRotate() {
        featuredMode = false
        updateChrome()
    }

    fun resumeRotate() = showFeaturedMode()

    fun showNext() {
        if (featured.size <= 1) return
        featuredMode = true
        index = (index + 1) % featured.size
        showItem(featured[index], updateDots = true)
        updateChrome()
    }

    fun showPrev() {
        if (featured.size <= 1) return
        featuredMode = true
        index = if (index <= 0) featured.lastIndex else index - 1
        showItem(featured[index], updateDots = true)
        updateChrome()
    }

    fun openCurrent() {
        val item = current ?: return
        onWatch(item)
    }

    fun release() {
        unbindHeroView(panel)
        runCatching { Glide.with(backdrop).clear(backdrop) }
        current = null
        featured = emptyList()
    }

    private fun updateChrome() {
        val showDots = featuredMode && featured.size > 1
        dots?.visibility = if (showDots) View.VISIBLE else View.INVISIBLE
        hint?.visibility =
            if (showDots && panel?.hasFocus() == true) View.VISIBLE else View.GONE
    }

    private fun refreshBoundPanel() {
        rebuildDots()
        val item = current ?: featured.getOrNull(index) ?: return
        showItem(item, updateDots = true)
        updateChrome()
    }

    private fun showItem(item: CatalogItem, updateDots: Boolean) {
        current = item
        badge?.text = badgeLabel(item)
        title?.text = item.displayTitle()
        meta?.text = buildMeta(item)
        val syn = item.parsedSinopsis().plot.trim()
        synopsis?.text = syn
        synopsis?.visibility = if (syn.isBlank()) View.GONE else View.VISIBLE

        val url = item.thumbnail_landscape?.takeIf { it.isNotBlank() }
            ?: item.thumbnail?.takeIf { it.isNotBlank() }
        loadBackdrop(url)

        if (updateDots) updateDots()
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
        val dotsView = dots ?: return
        dotsView.removeAllViews()
        if (featured.size <= 1) {
            dotsView.visibility = View.GONE
            return
        }
        dotsView.visibility = View.VISIBLE
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
            dotsView.addView(dot)
        }
        updateDots()
    }

    private fun updateDots() {
        val dotsView = dots ?: return
        for (i in 0 until dotsView.childCount) {
            val dot = dotsView.getChildAt(i)
            val bg = dot.background as? GradientDrawable ?: continue
            val density = context.resources.displayMetrics.density
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            if (i == index) {
                bg.setColor(ContextCompat.getColor(context, R.color.wu_accent))
                lp.width = (22 * density).toInt()
                lp.height = (8 * density).toInt()
                dot.layoutParams = lp
                bg.cornerRadius = 4 * density
            } else {
                bg.setColor(0x66FFFFFF)
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
        fun attach(
            activity: Activity,
            onWatch: (CatalogItem) -> Unit,
        ): HeroCarouselController? {
            val backdrop = activity.findViewById<ImageView>(R.id.browseBackdrop) ?: return null
            return HeroCarouselController(activity, backdrop, onWatch)
        }
    }
}
