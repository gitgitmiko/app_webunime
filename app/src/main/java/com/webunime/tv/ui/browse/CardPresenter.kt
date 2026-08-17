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
import android.text.TextUtils
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
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
import java.security.MessageDigest

/**
 * Kartu browse/search: poster 2:3 seukuran web (~6 per baris).
 * Judul wrap max 3 baris, lalu ellipsis. Badge di pojok kanan atas.
 */
class CardPresenter(
    private val onLibraryLongPress: ((CatalogItem) -> Boolean)? = null,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val (w, h) = sizePx(parent.context)
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setMainImageDimensions(w, h)
            cardType = ImageCardView.CARD_TYPE_INFO_OVER
            setBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
            setInfoAreaBackgroundColor(Color.argb(0xD4, 0, 0, 0))
            setupTitleWrap()
            setupFocusBehavior()
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val movie = item as? CatalogItem ?: return
        val card = viewHolder.view as ImageCardView
        card.setTag(R.id.tag_catalog_item, movie)

        card.titleText = movie.displayTitle()
        val ep = movie.episode
        card.contentText = when {
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
                    "continue" -> movie.durasi.orEmpty()
                    "favorite" -> ""
                    else -> movie.type?.replace('-', ' ')?.uppercase().orEmpty()
                }
            }
        }
        card.badgeImage = null

        val titleView = card.titleTextView()
        titleView?.let { applyTitleWrap(it) }

        applyCardSize(card)
        bindPoster(card, movie, force = false)
        bindLibraryLongPress(card, movie)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        val titleView = card.titleTextView()
        titleView?.let { tv ->
            (tv.getTag(R.id.tag_marquee_run) as? Runnable)?.let { tv.removeCallbacks(it) }
            tv.setTag(R.id.tag_marquee_run, null)
            applyTitleWrap(tv)
        }
        card.setTag(R.id.tag_catalog_item, null)
        card.setOnLongClickListener(null)
        card.setOnKeyListener(null)
        clearPosterRequest(card)
    }

    private fun bindLibraryLongPress(card: ImageCardView, movie: CatalogItem) {
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
        /** Sama seperti baris web (anime terbaru ~6 poster di view 100%). */
        const val VISIBLE_PER_ROW = 6

        fun gapPx(context: Context): Int =
            (12f * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)

        fun edgePadPx(context: Context): Int =
            (48f * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)

        /** Poster 2:3, lebar dihitung agar ~6 kartu masuk layar. */
        fun sizePx(context: Context): Pair<Int, Int> {
            val dm = context.resources.displayMetrics
            val gap = gapPx(context)
            val pad = edgePadPx(context)
            val usable = (dm.widthPixels - pad * 2 - gap * (VISIBLE_PER_ROW - 1))
                .coerceAtLeast((120f * dm.density).toInt() * VISIBLE_PER_ROW)
            val w = usable / VISIBLE_PER_ROW
            val h = w * 3 / 2
            return w to h
        }

        fun styleCatalogRow(grid: androidx.leanback.widget.HorizontalGridView) {
            val pad = edgePadPx(grid.context)
            grid.setItemSpacing(gapPx(grid.context))
            grid.setPadding(pad, grid.paddingTop, pad / 2, grid.paddingBottom)
            grid.clipToPadding = false
        }

        private fun applyCardSize(card: ImageCardView) {
            val (w, h) = sizePx(card.context)
            val sizeKey = "${w}x$h"
            if (card.getTag(R.id.tag_card_size) == sizeKey) return
            card.setTag(R.id.tag_card_size, sizeKey)
            card.setMainImageDimensions(w, h)
        }

        private fun applyTitleWrap(titleView: TextView) {
            titleView.isSingleLine = false
            titleView.setSingleLine(false)
            titleView.maxLines = 3
            titleView.minLines = 1
            titleView.ellipsize = TextUtils.TruncateAt.END
            titleView.setHorizontallyScrolling(false)
            titleView.isSelected = false
            titleView.marqueeRepeatLimit = 0
            titleView.isHorizontalFadingEdgeEnabled = false
            titleView.setLineSpacing(0f, 1.2f)
        }

        private fun ImageCardView.setupTitleWrap() {
            titleTextView()?.let { applyTitleWrap(it) }
            contentTextView()?.let { tv ->
                tv.isSingleLine = true
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
            }
        }

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
            label.endsWith("EPS") ->
                Color.argb(0xE6, 0x0D, 0x47, 0x6B)
            label.contains("CAM") || label.contains("TS") || label.contains("TC") ->
                Color.argb(0xE6, 0xB2, 0x5B, 0x00)
            label.contains("4K") || label.contains("UHD") || label.contains("BLU") ->
                Color.argb(0xE6, 0xE5, 0x09, 0x14)
            label == "HD" || label.contains("1080") || label.contains("720") ->
                Color.argb(0xE6, 0x1A, 0x1A, 0x1A)
            else -> Color.argb(0xE6, 0x2F, 0x2F, 0x2F)
        }

        private fun canUseGlide(context: Context): Boolean {
            val activity = context as? Activity ?: return true
            return !activity.isFinishing && !activity.isDestroyed
        }

        private fun bindPoster(card: ImageCardView, movie: CatalogItem, force: Boolean) {
            if (!canUseGlide(card.context)) return
            val (width, height) = sizePx(card.context)
            val sizeKey = "${width}x$height"
            val badge = movie.posterBadgeLabel().orEmpty()
            val portrait = movie.thumbnail?.takeIf { it.isNotBlank() }
            val landscape = movie.thumbnail_landscape?.takeIf { it.isNotBlank() }
            val alt = movie.thumbnailAlt?.takeIf { it.isNotBlank() && it != portrait }
            val urls = listOfNotNull(portrait, alt, landscape).distinct()
            val nextUrl = urls.firstOrNull()
            val bindKey = listOf(movie.slug.orEmpty(), nextUrl.orEmpty(), sizeKey, badge)
                .joinToString("|")

            if (!force && card.getTag(R.id.tag_bind_key) == bindKey) return

            cancelPosterRequest(card)
            card.setTag(R.id.tag_bind_key, bindKey)
            card.setTag(R.id.tag_thumb_url, nextUrl)
            card.setTag(R.id.tag_card_size, sizeKey)
            card.setTag(R.id.tag_quality, badge)
            card.mainImageView.setTag(R.id.tag_thumb_url, nextUrl)

            val placeholder = ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_surface))
            card.mainImage = placeholder
            if (nextUrl.isNullOrBlank()) return

            val corner = (4f * card.resources.displayMetrics.density).toInt().coerceAtLeast(4)
            val options = RequestOptions()
                .dontAnimate()
                .skipMemoryCache(false)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(width, height)
            loadIntoCard(card, bindKey, urls, 0, options, placeholder, corner, badge)
        }

        private fun cancelPosterRequest(card: ImageCardView) {
            val iv = card.mainImageView ?: return
            if (canUseGlide(card.context)) {
                runCatching { Glide.with(iv).clear(iv) }
            }
        }

        private fun clearPosterRequest(card: ImageCardView) {
            cancelPosterRequest(card)
            card.mainImage = ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_surface))
            card.setTag(R.id.tag_bind_key, null)
            card.setTag(R.id.tag_thumb_url, null)
            card.mainImageView?.setTag(R.id.tag_thumb_url, null)
        }

        fun preload(context: Context, url: String?) {
            val src = url?.takeIf { it.isNotBlank() } ?: return
            if (!canUseGlide(context)) return
            runCatching {
                val (w, h) = sizePx(context)
                Glide.with(context)
                    .load(src)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload(w, h)
            }
        }

        private fun loadIntoCard(
            card: ImageCardView,
            bindKey: String,
            urls: List<String>,
            index: Int,
            options: RequestOptions,
            placeholder: Drawable,
            corner: Int,
            badge: String,
        ) {
            val iv = card.mainImageView ?: return
            if (!canUseGlide(card.context)) return
            if (index >= urls.size) {
                if (card.getTag(R.id.tag_bind_key) == bindKey) {
                    card.mainImage = placeholder
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
                        loadIntoCard(
                            card, bindKey, urls, index + 1,
                            options, placeholder, corner, badge,
                        )
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean = card.getTag(R.id.tag_bind_key) != bindKey
                })
                .into(iv)
        }

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

        private fun ImageCardView.titleTextView(): TextView? =
            findViewById(androidx.leanback.R.id.title_text)

        private fun ImageCardView.contentTextView(): TextView? =
            findViewById(androidx.leanback.R.id.content_text)

        private fun ImageCardView.setupFocusBehavior() {
            setOnFocusChangeListener { _, _ ->
                applyCardSize(this)
                titleTextView()?.let { applyTitleWrap(it) }
                contentTextView()?.let { tv ->
                    tv.isSingleLine = true
                    tv.maxLines = 1
                    tv.ellipsize = TextUtils.TruncateAt.END
                }
            }
        }
    }
}
