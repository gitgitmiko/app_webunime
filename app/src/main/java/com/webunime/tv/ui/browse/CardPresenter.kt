package com.webunime.tv.ui.browse

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem

/**
 * Kartu browse/search ala Netflix:
 * Semua kategori (film / series / horor / anime):
 * tidak fokus = poster portrait; fokus = landscape 16:9.
 * Badge di pojok kanan atas: kualitas (film/horor) atau total EPS (series/anime).
 */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setMainImageDimensions(PORTRAIT_W, PORTRAIT_H)
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            setBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
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
            else -> movie.displayMeta().ifBlank { movie.type?.replace('-', ' ')?.uppercase().orEmpty() }
        }
        card.badgeImage = null

        val titleView = card.titleTextView()
        titleView?.let { tv ->
            (tv.getTag(R.id.tag_marquee_run) as? Runnable)?.let { tv.removeCallbacks(it) }
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.isSelected = false
        }

        applyCardSize(card, card.hasFocus())
        bindPoster(card, movie, force = true)

        if (card.hasFocus()) {
            titleView?.let { scheduleMarquee(card, it) }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        val titleView = card.titleTextView()
        titleView?.let { tv ->
            (tv.getTag(R.id.tag_marquee_run) as? Runnable)?.let { tv.removeCallbacks(it) }
            tv.setTag(R.id.tag_marquee_run, null)
            tv.isSelected = false
            tv.ellipsize = TextUtils.TruncateAt.END
        }
        card.setTag(R.id.tag_catalog_item, null)
        card.setTag(R.id.tag_thumb_url, null)
        card.setTag(R.id.tag_card_size, null)
        card.setTag(R.id.tag_quality, null)
        card.mainImageView.setTag(R.id.tag_thumb_url, null)
        card.badgeImage = null
        Glide.with(card).clear(card)
        card.mainImage = null
    }

    companion object {
        // Tidak fokus — poster ~2:3
        private const val PORTRAIT_W = 156
        private const val PORTRAIT_H = 234

        // Fokus — title card Netflix ~16:9
        private const val FOCUS_LANDSCAPE_W = 400
        private const val FOCUS_LANDSCAPE_H = 225

        private const val MARQUEE_DELAY_MS = 480L

        private fun cardSizeFor(focused: Boolean): Pair<Int, Int> =
            if (focused) FOCUS_LANDSCAPE_W to FOCUS_LANDSCAPE_H
            else PORTRAIT_W to PORTRAIT_H

        private fun applyCardSize(card: ImageCardView, focused: Boolean) {
            val (w, h) = cardSizeFor(focused)
            card.setMainImageDimensions(w, h)
            card.requestLayout()
            (card.parent as? ViewGroup)?.requestLayout()
        }

        /** Gambar badge (kualitas / total EPS) di pojok kanan atas bitmap poster. */
        private fun withPosterBadge(
            src: Bitmap,
            context: Context,
            badge: String?,
        ): Bitmap {
            val label = badge?.trim()?.takeIf { it.isNotBlank() } ?: return src
            val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
            val canvas = Canvas(out)
            val density = context.resources.displayMetrics.density
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
            val focused = card.hasFocus()
            val (width, height) = cardSizeFor(focused)
            val sizeKey = "${width}x$height"
            val badge = movie.posterBadgeLabel().orEmpty()
            val portrait = movie.thumbnail?.takeIf { it.isNotBlank() }
            val landscape = movie.thumbnail_landscape?.takeIf { it.isNotBlank() }
            val alt = movie.thumbnailAlt?.takeIf { it.isNotBlank() && it != portrait }
            // Fokus: landscape bila ada, lalu portrait (+ alt).
            // Unfocus: portrait dulu; landscape cadangan jika poster gagal/kosong.
            val urls = if (focused) {
                listOfNotNull(landscape, portrait, alt).distinct()
            } else {
                listOfNotNull(portrait, alt, landscape).distinct()
            }
            val nextUrl = urls.firstOrNull()
            val prevUrl = card.mainImageView.getTag(R.id.tag_thumb_url) as? String
            val prevSize = card.getTag(R.id.tag_card_size) as? String
            val prevBadge = card.getTag(R.id.tag_quality) as? String

            if (!force && prevUrl == nextUrl && prevSize == sizeKey && prevBadge == badge) return

            card.setTag(R.id.tag_thumb_url, nextUrl)
            card.setTag(R.id.tag_card_size, sizeKey)
            card.setTag(R.id.tag_quality, badge)
            card.mainImageView.setTag(R.id.tag_thumb_url, nextUrl)

            val placeholder = ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_bg))
            runCatching { Glide.with(card.context).clear(card.mainImageView) }
            card.mainImage = placeholder
            if (nextUrl == null) return

            val options = RequestOptions()
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .transform(CenterCrop(), RoundedCorners(12))
            loadIntoCard(card, urls, 0, options, placeholder, width, height, badge)
        }

        private fun loadIntoCard(
            card: ImageCardView,
            urls: List<String>,
            index: Int,
            options: RequestOptions,
            placeholder: Drawable,
            width: Int,
            height: Int,
            badge: String,
        ) {
            if (!canUseGlide(card.context)) return
            if (index >= urls.size) {
                card.mainImage = placeholder
                return
            }
            val url = urls[index]
            Glide.with(card.context)
                .asBitmap()
                .load(url)
                .apply(options)
                .into(object : CustomTarget<Bitmap>(width, height) {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?,
                    ) {
                        if (!canUseGlide(card.context)) return
                        if (card.getTag(R.id.tag_thumb_url) != urls.firstOrNull()) return
                        val stamped = withPosterBadge(resource, card.context, badge)
                        card.mainImage = BitmapDrawable(card.resources, stamped)
                    }

                    override fun onLoadCleared(placeholderDrawable: Drawable?) {
                        /* no-op */
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (!canUseGlide(card.context)) return
                        if (card.getTag(R.id.tag_thumb_url) != urls.firstOrNull()) return
                        loadIntoCard(card, urls, index + 1, options, placeholder, width, height, badge)
                    }
                })
        }

        private fun ImageCardView.titleTextView(): TextView? =
            findViewById(androidx.leanback.R.id.title_text)

        private fun ImageCardView.setupFocusBehavior() {
            val titleView = titleTextView() ?: return
            titleView.ellipsize = TextUtils.TruncateAt.END
            titleView.isSingleLine = true
            titleView.marqueeRepeatLimit = 2
            titleView.isHorizontalFadingEdgeEnabled = false

            setOnFocusChangeListener { _, hasFocus ->
                val item = getTag(R.id.tag_catalog_item) as? CatalogItem
                applyCardSize(this, hasFocus)
                if (item != null && canUseGlide(context)) {
                    bindPoster(this, item, force = true)
                }

                val tv = titleTextView() ?: return@setOnFocusChangeListener
                (tv.getTag(R.id.tag_marquee_run) as? Runnable)?.let { tv.removeCallbacks(it) }
                if (hasFocus) {
                    scheduleMarquee(this, tv)
                } else {
                    tv.isSelected = false
                    tv.ellipsize = TextUtils.TruncateAt.END
                }
            }
        }

        private fun scheduleMarquee(card: ImageCardView, titleView: TextView) {
            val start = Runnable {
                if (!card.hasFocus()) return@Runnable
                titleView.ellipsize = TextUtils.TruncateAt.MARQUEE
                titleView.isSelected = true
            }
            titleView.setTag(R.id.tag_marquee_run, start)
            titleView.postDelayed(start, MARQUEE_DELAY_MS)
        }
    }
}
