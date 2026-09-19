package com.webunime.tv.ui.browse

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.PosterGlide
import java.security.MessageDigest

/**
 * Kartu browse/search: poster 2:3 penuh di kiri, judul + meta di kanan.
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
        /** Kartu lebih lebar (poster + teks) → ~4 per baris. */
        const val VISIBLE_PER_ROW = 4

        data class Metrics(
            val posterW: Int,
            val posterH: Int,
            val cardW: Int,
            val cardH: Int,
            val infoW: Int,
        )

        fun gapPx(context: Context): Int =
            (14f * context.resources.displayMetrics.density).toInt().coerceAtLeast(10)

        fun edgePadPx(context: Context): Int =
            (48f * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)

        fun metricsPx(context: Context): Metrics {
            val dm = context.resources.displayMetrics
            val gap = gapPx(context)
            val pad = edgePadPx(context)
            val inner = (10f * dm.density).toInt().coerceAtLeast(8)
            val usable = (dm.widthPixels - pad * 2 - gap * (VISIBLE_PER_ROW - 1))
                .coerceAtLeast((200f * dm.density).toInt() * VISIBLE_PER_ROW)
            val cardW = usable / VISIBLE_PER_ROW
            val posterW = (cardW * 0.42f).toInt().coerceAtLeast((96f * dm.density).toInt())
            val posterH = posterW * 3 / 2
            val infoW = (cardW - posterW - inner).coerceAtLeast((88f * dm.density).toInt())
            return Metrics(posterW, posterH, cardW, posterH, infoW)
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
            card.posterView()?.layoutParams = LinearLayout.LayoutParams(m.posterW, m.posterH)
            card.infoView()?.layoutParams = LinearLayout.LayoutParams(m.infoW, m.cardH)
        }

        private fun View.posterView(): ImageView? = findViewById(R.id.catalog_poster)
        private fun View.titleView(): TextView? = findViewById(R.id.catalog_title)
        private fun View.metaView(): TextView? = findViewById(R.id.catalog_meta)
        private fun View.infoView(): View? = findViewById(R.id.catalog_info)

        /** Gambar badge (kualitas / total EPS) di pojok kanan atas bitmap poster. */
        private fun withPosterBadge(
            src: Bitmap,
            density: Float,
            badge: String?,
        ): Bitmap {
            val label = badge?.trim()?.takeIf { it.isNotBlank() } ?: return src
            val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
            val canvas = Canvas(out)
            val textSizePx = 13f * density
            val padH = 10f * density
            val padV = 5f * density
            val margin = 8f * density
            val radius = 5f * density

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = textSizePx
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isFakeBoldText = true
            }
            val textW = textPaint.measureText(label)
            val fm = textPaint.fontMetrics
            val textH = fm.descent - fm.ascent
            val badgeW = textW + padH * 2
            val badgeH = textH + padV * 2
            val left = (out.width - margin - badgeW).coerceAtLeast(0f)
            val top = margin
            val right = left + badgeW
            val bottom = top + badgeH

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = posterBadgeColor(label)
            }
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)
            val textX = left + padH
            val textY = top + padV - fm.ascent
            canvas.drawText(label, textX, textY, textPaint)
            return out
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
            val badge = movie.posterBadgeLabel().orEmpty()
            val portrait = movie.thumbnail?.takeIf { it.isNotBlank() }
            val landscape = movie.thumbnail_landscape?.takeIf { it.isNotBlank() }
            val alt = movie.thumbnailAlt?.takeIf { it.isNotBlank() && it != portrait }
            val sourceUrls = listOfNotNull(portrait, alt, landscape).distinct()
            val urls = sourceUrls.flatMap { PosterGlide.fallbackModels(it) }.distinct()
            val nextUrl = sourceUrls.firstOrNull()
            val bindKey = listOf(movie.slug.orEmpty(), nextUrl.orEmpty(), sizeKey, badge)
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
            card.setTag(R.id.tag_quality, badge)
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
            loadIntoCard(card, bindKey, urls, 0, options, placeholder, corner, badge)
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
            badge: String,
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
            val transforms = buildList {
                add(CenterCrop())
                add(RoundedCorners(corner))
                if (badge.isNotBlank()) {
                    add(PosterBadgeTransform(badge, card.resources.displayMetrics.density))
                }
            }
            Glide.with(iv)
                .load(url)
                .apply(options)
                .transform(*transforms.toTypedArray())
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
                                options, placeholder, corner, badge,
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

        private class PosterBadgeTransform(
            private val badge: String,
            private val density: Float,
        ) : BitmapTransformation() {
            override fun transform(
                pool: BitmapPool,
                toTransform: Bitmap,
                outWidth: Int,
                outHeight: Int,
            ): Bitmap = withPosterBadge(toTransform, density, badge)

            override fun equals(other: Any?): Boolean =
                other is PosterBadgeTransform && other.badge == badge

            override fun hashCode(): Int = ID.hashCode() * 31 + badge.hashCode()

            override fun updateDiskCacheKey(messageDigest: MessageDigest) {
                messageDigest.update((ID + badge).toByteArray(Charsets.UTF_8))
            }

            companion object {
                private const val ID = "com.webunime.tv.poster-badge"
            }
        }

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
