package com.webunime.tv.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.webunime.tv.R

/**
 * Presenter satu sel hero: fokusable, DPAD kiri/kanan & OK ditangani controller.
 */
class HeroPresenter(
    private val controller: () -> HeroCarouselController?,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hero_carousel, parent, false)
        val width = parent.resources.displayMetrics.widthPixels
        view.layoutParams = ViewGroup.LayoutParams(
            width,
            parent.resources.getDimensionPixelSize(R.dimen.browse_hero_row_height),
        )
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isClickable = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        controller()?.bindHeroView(viewHolder.view)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        controller()?.unbindHeroView(viewHolder.view)
    }
}
