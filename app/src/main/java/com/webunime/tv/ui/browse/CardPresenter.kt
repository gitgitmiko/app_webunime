package com.webunime.tv.ui.browse

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.PosterGlide

/**
 * Kartu browse/search: poster 2:3 di atas, judul + meta di bawah.
 * Badge HD/CAM overlay pojok kanan atas di dalam poster.
 */
class CardPresenter(
    private val onLibraryLongPress: ((CatalogItem) -> Boolean)? = null,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_catalog_card, parent, false)
            .apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                clipToOutline = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    foreground = ContextCompat.getDrawable(context, R.drawable.bg_card_focus_ring)
                }
                applyCardSize(this)
                setupFocusBehavior()
            }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val movie = item as? CatalogItem ?: return
        val card = viewHolder.view
        card.setTag(R.id.tag_catalog_item, movie)

        card.titleView()?.text = movie.displayTitle()
        val ep = movie.episode
        card.metaView()?.text = when {
            movie.type == "continue" -> {
                val parts = mutableListOf<String>()
                if (ep != null && ep > 0) parts += "Episode $ep"
                movie.durasi?.takeIf { it.isNotBlank() }?.let { parts += it }
                parts.joinToString(" · ")
            }
            ep != null && ep > 0 && (movie.anime_slug != null || movie.series_slug != null) -> {
                val season = movie.season?.takeIf { it > 0 }
                if (movie.series_slug != null && season != null && season > 1) {
                    "S$season · Episode $ep"
                } else {
                    "Episode $ep"
                }
            }
            else -> {
                val meta = movie.displayMeta()
                if (meta.isNotBlank()) meta
                else when (movie.type) {
                    "favorite" -> ""
                    else -> movie.type?.replace('-', ' ')?.uppercase().orEmpty()
                }
            }
        }

        applyCardSize(card)
        bindBadge(card, movie.posterBadgeLabel())
        bindPoster(card, movie, force = false)
        bindLibraryLongPress(card, movie)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view
        card.setTag(R.id.tag_catalog_item, null)
        card.setOnLongClickListener(null)
        card.setOnKeyListener(null)
        card.animate().cancel()
        card.scaleX = 1f
        card.scaleY = 1f
        card.badgeView()?.visibility = View.GONE
        clearPosterRequest(card)
    }

    private fun bindLibraryLongPress(card: View, movie: CatalogItem) {
        val library = movie.type == "continue" || movie.type == "favorite"
        if (!library || onLibraryLongPress == null) {
            card.setOnLongClickListener(null)
            card.setOnKeyListener(null)
            return
        }
        card.setOnLongClickListener {
            onLibraryLongPress.invoke(movie)
        }
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_INFO) {
                return@setOnKeyListener false
            }
            onLibraryLongPress.invoke(movie)
        }
    }

    companion object {
        /** Poster penuh + judul di bawah → ~4 per baris (ukuran poster mirip sebelumnya). */
        const val VISIBLE_PER_ROW = 4

        data class Metrics(
            val posterW: Int,
            val posterH: Int,
            val cardW: Int,
            val cardH: Int,
            val infoH: Int,
        )

        fun gapPx(context: Context): Int =
            (14f * context.resources.displayMetrics.density).toInt().coerceAtLeast(10)

        fun edgePadPx(context: Context): Int =
            (40f * context.resources.displayMetrics.density).toInt().coerceAtLeast(28)

        fun metricsPx(context: Context): Metrics {
            val dm = context.resources.displayMetrics
            val gap = gapPx(context)
            val pad = edgePadPx(context)
            val usable = (dm.widthPixels - pad * 2 - gap * (VISIBLE_PER_ROW - 1))
                .coerceAtLeast((160f * dm.density).toInt() * VISIBLE_PER_ROW)
            // Lebar kartu = lebar poster (judul di bawah, full width).
            val posterW = (usable / VISIBLE_PER_ROW).coerceAtLeast((120f * dm.density).toInt())
            val posterH = posterW * 3 / 2
            val infoH = (56f * dm.density).toInt().coerceAtLeast(48)
            val cardW = posterW
            val cardH = posterH + infoH
            return Metrics(posterW, posterH, cardW, cardH, infoH)
        }

        /** Ukuran poster (Glide / placeholder loading). */
        fun sizePx(context: Context): Pair<Int, Int> {
            val m = metricsPx(context)
            return m.posterW to m.posterH
        }

        fun styleCatalogRow(grid: androidx.leanback.widget.HorizontalGridView) {
            val pad = edgePadPx(grid.context)
            grid.setItemSpacing(gapPx(grid.context))
            grid.setPadding(pad, grid.paddingTop, pad / 2, grid.paddingBottom)
            grid.clipToPadding = false
        }

        private fun applyCardSize(card: View) {
            val m = metricsPx(card.context)
            val sizeKey = "${m.cardW}x${m.cardH}"
            if (card.getTag(R.id.tag_card_size) == sizeKey) return
            card.setTag(R.id.tag_card_size, sizeKey)
            card.layoutParams = ViewGroup.LayoutParams(m.cardW, m.cardH)
            card.posterWrap()?.layoutParams = LinearLayout.LayoutParams(m.posterW, m.posterH)
            card.infoView()?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                m.infoH,
            )
            card.clipToOutline = true
            card.invalidateOutline()
        }

        private fun View.posterWrap(): FrameLayout? = findViewById(R.id.catalog_poster_wrap)
        private fun View.posterView(): ImageView? = findViewById(R.id.catalog_poster)
        private fun View.badgeView(): TextView? = findViewById(R.id.catalog_badge)
        private fun View.titleView(): TextView? = findViewById(R.id.catalog_title)
        private fun View.metaView(): TextView? = findViewById(R.id.catalog_meta)
        private fun View.infoView(): View? = findViewById(R.id.catalog_info)

        private fun bindBadge(card: View, raw: String?) {
            val badge = card.badgeView() ?: return
            val label = raw?.trim()?.takeIf { it.isNotBlank() }
            if (label == null) {
                badge.visibility = View.GONE
                badge.text = ""
                return
            }
            badge.text = label
            badge.background = badgeBackground(card.context, label)
            badge.visibility = View.VISIBLE
        }

        private fun badgeBackground(context: Context, label: String): Drawable {
            val density = context.resources.displayMetrics.density
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5f * density
                setColor(posterBadgeColor(label))
            }
        }

        private fun posterBadgeColor(label: String): Int = when {
            label == "NEW" ->
                Color.argb(0xE6, 0x00, 0x8A, 0x3E)
            label.endsWith("EPS") || Regex("^E\\d+$").matches(label) ->
                Color.argb(0xE6, 0x0D, 0x47, 0x6B)
            label.contains("CAM") || label.contains("TS") || label.contains("TC") ->
                Color.argb(0xE6, 0xB2, 0x5B, 0x00)
            label.contains("4K") || label.contains("UHD") || label.contains("BLU") ->
                Color.argb(0xE6, 0xFF, 0x2D, 0x3A)
            label == "HD" || label.contains("1080") || label.contains("720") ->
                Color.argb(0xE6, 0x1A, 0x1A, 0x1A)
            else -> Color.argb(0xE6, 0x2F, 0x2F, 0x2F)
        }

        private fun canUseGlide(context: Context): Boolean {
            val activity = context as? Activity ?: return true
            return !activity.isFinishing && !activity.isDestroyed
        }

        private fun bindPoster(card: View, movie: CatalogItem, force: Boolean) {
            if (!canUseGlide(card.context)) return
            val m = metricsPx(card.context)
            val width = m.posterW
            val height = m.posterH
            val sizeKey = "${m.cardW}x${m.cardH}"
            val portrait = movie.thumbnail?.takeIf { it.isNotBlank() }
            val landscape = movie.thumbnail_landscape?.takeIf { it.isNotBlank() }
            val alt = movie.thumbnailAlt?.takeIf { it.isNotBlank() && it != portrait }
            val sourceUrls = listOfNotNull(portrait, alt, landscape).distinct()
            val urls = sourceUrls.flatMap { PosterGlide.fallbackModels(it) }.distinct()
            val nextUrl = sourceUrls.firstOrNull()
            val bindKey = listOf(movie.slug.orEmpty(), nextUrl.orEmpty(), sizeKey)
                .joinToString("|")

            if (!force && card.getTag(R.id.tag_bind_key) == bindKey) {
                if (card.getTag(R.id.tag_poster_ok) == bindKey) return
                if (card.getTag(R.id.tag_poster_loading) == bindKey) return
            }

            cancelPosterRequest(card)
            card.setTag(R.id.tag_bind_key, bindKey)
            card.setTag(R.id.tag_poster_ok, null)
            card.setTag(R.id.tag_poster_loading, bindKey)
            card.setTag(R.id.tag_thumb_url, nextUrl)
            card.setTag(R.id.tag_card_size, sizeKey)
            card.posterView()?.setTag(R.id.tag_thumb_url, nextUrl)

            val placeholder = ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_surface))
            card.posterView()?.setImageDrawable(placeholder)
            if (nextUrl.isNullOrBlank()) {
                card.setTag(R.id.tag_poster_loading, null)
                return
            }

            val corner = card.resources.getDimensionPixelSize(R.dimen.card_corner_radius)
                .coerceAtLeast(8)
            val options = RequestOptions()
                .dontAnimate()
                .skipMemoryCache(false)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(width, height)
            loadIntoCard(card, bindKey, urls, 0, options, placeholder, corner)
        }

        private fun cancelPosterRequest(card: View) {
            val iv = card.posterView() ?: return
            if (canUseGlide(card.context)) {
                runCatching { Glide.with(iv).clear(iv) }
            }
        }

        private fun clearPosterRequest(card: View) {
            cancelPosterRequest(card)
            card.posterView()?.setImageDrawable(
                ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_surface)),
            )
            card.setTag(R.id.tag_bind_key, null)
            card.setTag(R.id.tag_poster_ok, null)
            card.setTag(R.id.tag_poster_loading, null)
            card.setTag(R.id.tag_thumb_url, null)
            card.posterView()?.setTag(R.id.tag_thumb_url, null)
        }

        fun preload(context: Context, url: String?) {
            val src = url?.takeIf { it.isNotBlank() } ?: return
            if (!canUseGlide(context)) return
            runCatching {
                val (w, h) = sizePx(context)
                Glide.with(context)
                    .load(PosterGlide.model(src))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload(w, h)
            }
        }

        private fun loadIntoCard(
            card: View,
            bindKey: String,
            urls: List<Any>,
            index: Int,
            options: RequestOptions,
            placeholder: Drawable,
            corner: Int,
        ) {
            val iv = card.posterView() ?: return
            if (!canUseGlide(card.context)) return
            if (index >= urls.size) {
                if (card.getTag(R.id.tag_bind_key) == bindKey) {
                    card.setTag(R.id.tag_poster_loading, null)
                    card.setTag(R.id.tag_poster_ok, null)
                    iv.setImageDrawable(placeholder)
                }
                return
            }
            val url = urls[index]
            Glide.with(iv)
                .load(url)
                .apply(options)
                .transform(CenterCrop(), RoundedCorners(corner))
                .placeholder(placeholder)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (!canUseGlide(card.context)) return true
                        if (card.getTag(R.id.tag_bind_key) != bindKey) return true
                        mainHandler.post {
                            if (!canUseGlide(card.context)) return@post
                            if (card.getTag(R.id.tag_bind_key) != bindKey) return@post
                            loadIntoCard(
                                card, bindKey, urls, index + 1,
                                options, placeholder, corner,
                            )
                        }
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (card.getTag(R.id.tag_bind_key) != bindKey) return true
                        card.setTag(R.id.tag_poster_ok, bindKey)
                        card.setTag(R.id.tag_poster_loading, null)
                        return false
                    }
                })
                .into(iv)
        }

        private val mainHandler = Handler(Looper.getMainLooper())

        private fun View.setupFocusBehavior() {
            val accent = ContextCompat.getColor(context, R.color.wu_accent_soft)
            val titleNormal = ContextCompat.getColor(context, R.color.wu_text)
            val dim = ContextCompat.getColor(context, R.color.wu_text_dim)
            setOnFocusChangeListener { v, hasFocus ->
                applyCardSize(this)
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
                v.animate()
                    .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                    .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                    .setDuration(FOCUS_ANIM_MS)
                    .start()
                titleView()?.setTextColor(if (hasFocus) accent else titleNormal)
                metaView()?.setTextColor(if (hasFocus) titleNormal else dim)
                infoView()?.setBackgroundColor(
                    if (hasFocus) {
                        ContextCompat.getColor(context, R.color.wu_card_info_focus)
                    } else {
                        Color.TRANSPARENT
                    },
                )
            }
        }

        private const val FOCUS_SCALE = 1.05f
        private const val FOCUS_ANIM_MS = 160L
    }
}
