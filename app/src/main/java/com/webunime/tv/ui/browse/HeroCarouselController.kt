package com.webunime.tv.ui.browse

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.AmbientBlur
import com.webunime.tv.ui.PosterGlide

/**
 * Hero carousel: backdrop blur ambient + poster tajam di kanan + teks di kiri.
 * Geser ←/→ (auto-geser tiap [AUTO_ADVANCE_MS] selama hero fokus); OK buka detail.
 * Backdrop hanya di-update saat mode hero — tidak mengikuti fokus kartu katalog.
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
    private var textColumn: View? = null
    private var posterWrap: View? = null
    private var poster: ImageView? = null
    private var lastPosterUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoAdvance = Runnable {
        if (panel?.hasFocus() == true && featuredMode) {
            showNext(animate = true)
        }
        scheduleAutoAdvance()
    }
    private val crossFade = DrawableTransitionOptions.with(
        DrawableCrossFadeFactory.Builder(CROSSFADE_MS).setCrossFadeEnabled(true).build(),
    )
    private var badge: TextView? = null
    private var quality: TextView? = null
    private var country: TextView? = null
    private var newBadge: TextView? = null
    private var title: TextView? = null
    private var meta: TextView? = null
    private var genres: LinearLayout? = null
    private var synopsis: TextView? = null
    private var dots: LinearLayout? = null

    private val keyListener = View.OnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showPrev()
                scheduleAutoAdvance()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showNext()
                scheduleAutoAdvance()
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
        quality = view.findViewById(R.id.browseHeroQuality)
        country = view.findViewById(R.id.browseHeroCountry)
        newBadge = view.findViewById(R.id.browseHeroNew)
        title = view.findViewById(R.id.browseHeroTitle)
        meta = view.findViewById(R.id.browseHeroMeta)
        genres = view.findViewById(R.id.browseHeroGenres)
        synopsis = view.findViewById(R.id.browseHeroSynopsis)
        dots = view.findViewById(R.id.browseHeroDots)
        textColumn = view.findViewById(R.id.browseHeroText)
        posterWrap = view.findViewById(R.id.browseHeroPosterWrap)
        poster = view.findViewById(R.id.browseHeroPoster)
        lastPosterUrl = null

        view.setOnKeyListener(keyListener)
        view.setOnClickListener {
            val item = current ?: return@setOnClickListener
            onWatch(item)
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showFeaturedMode()
                scheduleAutoAdvance()
            } else {
                handler.removeCallbacks(autoAdvance)
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
        handler.removeCallbacks(autoAdvance)
        poster?.let { iv -> runCatching { Glide.with(iv).clear(iv) } }
        textColumn = null
        posterWrap = null
        poster = null
        lastPosterUrl = null
        panel = null
        badge = null
        quality = null
        country = null
        newBadge = null
        title = null
        meta = null
        genres = null
        synopsis = null
        dots = null
    }

    fun setFeatured(items: List<CatalogItem>) {
        featured = items.take(10)
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

    /** Fokus kartu katalog: hentikan rotate hero, backdrop tetap di gambar hero terakhir. */
    fun onBrowseItemFocused(item: CatalogItem?) {
        if (item == null) return
        pauseRotate()
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
        handler.removeCallbacks(autoAdvance)
        updateChrome()
    }

    fun resumeRotate() = showFeaturedMode()

    fun showNext(animate: Boolean = true) {
        if (featured.size <= 1) return
        featuredMode = true
        index = (index + 1) % featured.size
        showItem(featured[index], updateDots = true, animate = animate)
        updateChrome()
    }

    fun showPrev() {
        if (featured.size <= 1) return
        featuredMode = true
        index = if (index <= 0) featured.lastIndex else index - 1
        showItem(featured[index], updateDots = true, animate = true)
        updateChrome()
    }

    private fun scheduleAutoAdvance() {
        handler.removeCallbacks(autoAdvance)
        if (featured.size > 1 && panel?.hasFocus() == true) {
            handler.postDelayed(autoAdvance, AUTO_ADVANCE_MS)
        }
    }

    fun openCurrent() {
        val item = current ?: return
        onWatch(item)
    }

    fun release() {
        handler.removeCallbacks(autoAdvance)
        unbindHeroView(panel)
        runCatching { Glide.with(backdrop).clear(backdrop) }
        current = null
        featured = emptyList()
    }

    private fun updateChrome() {
        val showDots = featuredMode && featured.size > 1
        dots?.visibility = if (showDots) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshBoundPanel() {
        rebuildDots()
        val item = current ?: featured.getOrNull(index) ?: return
        showItem(item, updateDots = true)
        updateChrome()
    }

    private fun showItem(item: CatalogItem, updateDots: Boolean, animate: Boolean = false) {
        val changed = current?.slug != item.slug || current?.displayTitle() != item.displayTitle()
        current = item
        if (animate && changed) {
            textColumn?.let { col ->
                col.animate().cancel()
                col.alpha = 0f
                col.translationX = 24f * context.resources.displayMetrics.density
                col.animate().alpha(1f).translationX(0f).setDuration(TEXT_ANIM_MS).start()
            }
        }
        badge?.text = badgeLabel(item)
        bindQuality(item)
        bindCountry(item)
        newBadge?.visibility = if (item.showsNewBadge()) View.VISIBLE else View.GONE
        title?.text = item.displayTitle()
        meta?.text = buildMeta(item)
        bindGenres(item)
        val syn = item.parsedSinopsis().plot.trim()
        synopsis?.text = syn
        synopsis?.visibility = if (syn.isBlank()) View.GONE else View.VISIBLE

        val portrait = item.thumbnail?.takeIf { it.isNotBlank() }
        val landscape = item.thumbnail_landscape?.takeIf { it.isNotBlank() }
        val alt = item.thumbnailAlt?.takeIf { it.isNotBlank() }
        // Backdrop di-blur → sumber apa pun aman; poster depan pakai gambar asli.
        loadBackdrop(landscape ?: portrait ?: alt)
        loadPoster(portrait ?: alt ?: landscape, fallback = alt?.takeIf { it != portrait })

        if (updateDots) updateDots()
    }

    private fun bindQuality(item: CatalogItem) {
        val label = item.quality?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
        val view = quality ?: return
        if (label == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = label
        applyChipBackground(view, qualityBadgeColor(label))
    }

    private fun bindCountry(item: CatalogItem) {
        val view = country ?: return
        val raw = item.displayCountry()
        if (raw == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = HeroBadgeStyles.countryBadgeText(raw)
        applyChipBackground(view, HeroBadgeStyles.countryColor(raw))
    }

    private fun bindGenres(item: CatalogItem) {
        val container = genres ?: return
        container.removeAllViews()
        val list = item.genre.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_GENRE_BADGES)
        if (list.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (4 * density).toInt()
        val gap = (6 * density).toInt()
        for ((i, genre) in list.withIndex()) {
            val chip = TextView(context).apply {
                text = genre
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.wu_text))
                setPadding(padH, padV, padH, padV)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                background = GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(HeroBadgeStyles.genreColor(genre))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { lp ->
                    if (i > 0) lp.marginStart = gap
                }
            }
            container.addView(chip)
        }
    }

    private fun applyChipBackground(view: TextView, color: Int) {
        val bg = (view.background as? GradientDrawable)?.mutate() as? GradientDrawable
            ?: GradientDrawable().apply {
                cornerRadius = 6f * context.resources.displayMetrics.density
                view.background = this
            }
        bg.setColor(color)
    }

    private fun qualityBadgeColor(label: String): Int = when {
        label.contains("CAM") || label.contains("TS") || label.contains("TC") ->
            Color.argb(0xE6, 0xB2, 0x5B, 0x00)
        label.contains("4K") || label.contains("UHD") || label.contains("BLU") ->
            Color.argb(0xE6, 0xFF, 0x2D, 0x3A)
        label == "HD" || label.contains("1080") || label.contains("720") ->
            Color.argb(0xE6, 0x1A, 0x1A, 0x1A)
        else -> Color.argb(0xE6, 0x2F, 0x2F, 0x2F)
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
            .load(PosterGlide.model(url))
            .transform(AmbientBlur())
            .transition(crossFade)
            .error(ColorDrawable(ContextCompat.getColor(context, R.color.wu_bg)))
            .into(backdrop)
    }

    /**
     * Poster tajam di kanan hero. Ukuran kartu menyesuaikan rasio gambar:
     * portrait → 2:3, landscape (screenshot episode anime, dll.) → 16:9.
     */
    private fun loadPoster(url: String?, fallback: String?) {
        val iv = poster ?: return
        val wrap = posterWrap ?: return
        if (url.isNullOrBlank()) {
            lastPosterUrl = null
            runCatching { Glide.with(iv).clear(iv) }
            wrap.visibility = View.INVISIBLE
            return
        }
        if (url == lastPosterUrl) return
        lastPosterUrl = url
        wrap.visibility = View.VISIBLE
        val listener = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean = false

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                applyPosterShape(resource.intrinsicWidth > resource.intrinsicHeight * 11 / 10)
                return false
            }
        }
        var req = Glide.with(iv)
            .load(PosterGlide.model(url))
            .transition(crossFade)
            .listener(listener)
        if (!fallback.isNullOrBlank()) {
            req = req.error(
                Glide.with(iv).load(PosterGlide.model(fallback)).listener(listener),
            )
        }
        req.into(iv)
    }

    private fun applyPosterShape(landscape: Boolean) {
        val wrap = posterWrap ?: return
        val res = context.resources
        val w = res.getDimensionPixelSize(if (landscape) R.dimen.hero_poster_land_w else R.dimen.hero_poster_w)
        val h = res.getDimensionPixelSize(if (landscape) R.dimen.hero_poster_land_h else R.dimen.hero_poster_h)
        val lp = wrap.layoutParams ?: return
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        wrap.layoutParams = lp
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
        val size = (6 * density).toInt()
        val gap = (6 * density).toInt()
        for (i in featured.indices) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    if (i > 0) it.marginStart = gap
                }
                background = GradientDrawable().apply {
                    cornerRadius = 3 * density
                    setColor(0x4DFFFFFF)
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
            lp.height = (6 * density).toInt()
            bg.cornerRadius = 3 * density
            if (i == index) {
                bg.setColor(ContextCompat.getColor(context, R.color.wu_text))
                lp.width = (28 * density).toInt()
            } else {
                bg.setColor(0x4DFFFFFF)
                lp.width = (6 * density).toInt()
            }
            dot.layoutParams = lp
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
        return parts.joinToString("   •   ")
    }

    companion object {
        private const val MAX_GENRE_BADGES = 4
        private const val AUTO_ADVANCE_MS = 8_000L
        private const val CROSSFADE_MS = 350
        private const val TEXT_ANIM_MS = 260L

        fun attach(
            activity: Activity,
            onWatch: (CatalogItem) -> Unit,
        ): HeroCarouselController? {
            val backdrop = activity.findViewById<ImageView>(R.id.browseBackdrop) ?: return null
            return HeroCarouselController(activity, backdrop, onWatch)
        }
    }
}
