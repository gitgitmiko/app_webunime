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
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem

/**
 * Kartu browse/search:
 * - Film / series / horor → landscape 16:9
 * - Anime (termasuk Anime Terbaru & anime movie) → portrait 2:3 seperti semula
 */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            // Default landscape; diganti di onBind sesuai jenis item.
            setMainImageDimensions(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            setBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
            setupTitleMarquee()
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val movie = item as? CatalogItem ?: return
        val card = viewHolder.view as ImageCardView
        card.titleText = movie.displayTitle()
        val ep = movie.episode
        card.contentText = when {
            ep != null && ep > 0 && movie.anime_slug != null -> "Episode $ep"
            else -> movie.displayMeta().ifBlank { movie.type?.replace('-', ' ')?.uppercase().orEmpty() }
        }
        // Pastikan judul panjang bisa marquee saat kartu fokus.
        card.titleTextView()?.isSelected = card.hasFocus()

        val portrait = isAnimeStyle(movie)
        val width = if (portrait) PORTRAIT_WIDTH else LANDSCAPE_WIDTH
        val height = if (portrait) PORTRAIT_HEIGHT else LANDSCAPE_HEIGHT
        card.setMainImageDimensions(width, height)

        val placeholder = ColorDrawable(ContextCompat.getColor(card.context, R.color.wu_bg))
        val primary = movie.thumbnail?.takeIf { it.isNotBlank() }
        val alt = movie.thumbnailAlt?.takeIf { it.isNotBlank() && it != primary }
        val urls = listOfNotNull(primary, alt)

        card.setTag(R.id.tag_thumb_url, urls.firstOrNull())
        Glide.with(card).clear(card)
        card.mainImage = placeholder

        if (urls.isEmpty()) return

        val options = RequestOptions()
            .dontAnimate()
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

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.setTag(R.id.tag_thumb_url, null)
        card.titleTextView()?.isSelected = false
        card.badgeImage = null
        Glide.with(card).clear(card)
        card.mainImage = null
    }

    companion object {
        private const val LANDSCAPE_WIDTH = 280
        private const val LANDSCAPE_HEIGHT = 158
        // Poster anime ~2:3 (seperti semula)
        private const val PORTRAIT_WIDTH = 200
        private const val PORTRAIT_HEIGHT = 300

        fun isAnimeStyle(item: CatalogItem): Boolean {
            if (!item.anime_slug.isNullOrBlank()) return true
            val type = item.type.orEmpty()
            return type == "anime" || type == "anime-movie" || type.startsWith("anime")
        }

        private fun ImageCardView.titleTextView(): TextView? =
            findViewById(androidx.leanback.R.id.title_text)

        private fun ImageCardView.setupTitleMarquee() {
            val titleView = titleTextView() ?: return
            titleView.ellipsize = TextUtils.TruncateAt.MARQUEE
            titleView.isSingleLine = true
            titleView.marqueeRepeatLimit = -1
            titleView.isHorizontalFadingEdgeEnabled = true
            titleView.setFadingEdgeLength(24)
            // Marquee Android hanya jalan jika view "selected".
            setOnFocusChangeListener { _, hasFocus ->
                titleTextView()?.isSelected = hasFocus
            }
        }
    }
}
