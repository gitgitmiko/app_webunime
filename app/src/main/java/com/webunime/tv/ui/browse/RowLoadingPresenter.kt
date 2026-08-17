package com.webunime.tv.ui.browse

import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.webunime.tv.R

/**
 * Kartu loading untuk baris lazy vertikal (Series/Anime parse lama).
 */
class RowLoadingPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val (w, h) = CardPresenter.sizePx(ctx)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = false
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.wu_surface_soft))
            layoutParams = ViewGroup.LayoutParams(w, h)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        val spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val label = TextView(ctx).apply {
            id = R.id.row_loading_label
            setTextColor(ContextCompat.getColor(ctx, R.color.wu_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(14)
            }
        }
        card.addView(spinner)
        card.addView(label)

        val wrap = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(w, h)
            addView(card)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return ViewHolder(wrap)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val loading = item as? RowLoadingItem ?: return
        val label = viewHolder.view.findViewById<TextView>(R.id.row_loading_label)
        label?.text = loading.message
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
