package com.webunime.tv.ui.browse

import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.webunime.tv.R
import com.webunime.tv.data.CatalogItem

/** Kartu poster vertikal ala Netflix. */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            cardType = ImageCardView.CARD_TYPE_INFO_UNDER
            setBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.wu_bg))
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
        card.mainImage = null
        val thumb = movie.thumbnail
        if (!thumb.isNullOrBlank()) {
            Glide.with(card.context)
                .load(thumb)
                .transform(CenterCrop(), RoundedCorners(12))
                .into(card.mainImageView)
        } else {
            card.mainImage =
                ContextCompat.getDrawable(card.context, R.drawable.ic_launcher_foreground) as Drawable?
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.badgeImage = null
        card.mainImage = null
        Glide.with(card.context).clear(card.mainImageView)
    }

    companion object {
        // Rasio poster ~2:3 (Netflix)
        private const val CARD_WIDTH = 200
        private const val CARD_HEIGHT = 300
    }
}
