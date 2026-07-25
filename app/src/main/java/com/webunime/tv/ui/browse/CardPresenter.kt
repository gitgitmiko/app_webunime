package com.webunime.tv.ui.browse

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
            ep != null && ep > 0 && movie.anime_slug != null -> "Episode $ep"
            else -> movie.displayMeta().ifBlank { movie.type?.replace('-', ' ')?.uppercase().orEmpty() }
        }

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

        private fun bindPoster(card: ImageCardView, movie: CatalogItem, force: Boolean) {
            val (width, height) = cardSizeFor(card.hasFocus())
            val sizeKey = "${width}x$height"
            val primary = movie.thumbnail?.takeIf { it.isNotBlank() }
            val alt = movie.thumbnailAlt?.takeIf { it.isNotBlank() && it != primary }
            val urls = listOfNotNull(primary, alt)
            val nextUrl = urls.firstOrNull()
            val prevUrl = card.mainImageView.getTag(R.id.tag_thumb_url) as? String
            val prevSize = card.getTag(R.id.tag_card_size) as? String

            if (!force && prevUrl == nextUrl && prevSize == sizeKey) return

            card.setTag(R.id.tag_thumb_url, nextUrl)
            card.setTag(R.id.tag_card_size, sizeKey)
            card.mainImageView.setTag(R.id.tag_thumb_url, nextUrl)

            val placeholder = ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_bg))
            Glide.with(card).clear(card)
            card.mainImage = placeholder
            if (nextUrl == null) return

            val options = RequestOptions()
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .transform(CenterCrop(), RoundedCorners(12))
            loadIntoCard(card, urls, 0, options, placeholder, width, height)
        }

        private fun loadIntoCard(
            card: ImageCardView,
            urls: List<String>,
            index: Int,
            options: RequestOptions,
            placeholder: Drawable,
            width: Int,
            height: Int,
        ) {
            if (index >= urls.size) {
                card.mainImage = placeholder
                return
            }
            val url = urls[index]
            Glide.with(card)
                .asBitmap()
                .load(url)
                .apply(options)
                .into(object : CustomTarget<android.graphics.Bitmap>(width, height) {
                    override fun onResourceReady(
                        resource: android.graphics.Bitmap,
                        transition: Transition<in android.graphics.Bitmap>?,
                    ) {
                        if (card.getTag(R.id.tag_thumb_url) != urls.firstOrNull()) return
                        card.mainImage = BitmapDrawable(card.resources, resource)
                    }

                    override fun onLoadCleared(placeholderDrawable: Drawable?) {
                        /* no-op */
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (card.getTag(R.id.tag_thumb_url) != urls.firstOrNull()) return
                        loadIntoCard(card, urls, index + 1, options, placeholder, width, height)
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
                if (item != null) {
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
