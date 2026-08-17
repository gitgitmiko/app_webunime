package com.webunime.tv.ui.browse

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.SearchOrbView
import androidx.leanback.widget.TitleViewAdapter
import com.webunime.tv.R

/**
 * Title Leanback: SearchOrb + SettingsOrb di kiri, nama user + wordmark di kanan.
 */
class WebunimeTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.leanback.R.attr.browseTitleViewStyle,
) : FrameLayout(context, attrs, defStyleAttr), TitleViewAdapter.Provider {

    private val badgeView: ImageView
    private val textView: TextView
    private val searchOrbView: SearchOrbView
    private val settingsOrbView: SearchOrbView

    private var flags: Int = TitleViewAdapter.FULL_VIEW_VISIBLE
    private var hasSearchListener = false
    private var hasSettingsListener = false

    private val titleViewAdapter = object : TitleViewAdapter() {
        override fun getSearchAffordanceView(): View = searchOrbView

        override fun setOnSearchClickedListener(listener: OnClickListener?) {
            this@WebunimeTitleView.setOnSearchClickedListener(listener)
        }

        override fun setAnimationEnabled(enable: Boolean) {
            enableAnimation(enable)
        }

        override fun getBadgeDrawable(): Drawable? = badgeView.drawable

        override fun getSearchAffordanceColors(): SearchOrbView.Colors =
            searchOrbView.orbColors

        override fun getTitle(): CharSequence? = textView.text

        override fun setBadgeDrawable(drawable: Drawable?) {
            this@WebunimeTitleView.setBadgeDrawable(drawable)
        }

        override fun setSearchAffordanceColors(colors: SearchOrbView.Colors) {
            this@WebunimeTitleView.setSearchAffordanceColors(colors)
        }

        override fun setTitle(titleText: CharSequence?) {
            this@WebunimeTitleView.setTitle(titleText)
        }

        override fun updateComponentsVisibility(flags: Int) {
            this@WebunimeTitleView.updateComponentsVisibility(flags)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.wu_title_view, this, true)
        badgeView = findViewById(R.id.title_badge)
        textView = findViewById(R.id.title_text)
        searchOrbView = findViewById(R.id.title_orb)
        settingsOrbView = findViewById(R.id.settings_orb)

        settingsOrbView.orbIcon = ContextCompat.getDrawable(context, R.drawable.ic_settings)
        settingsOrbView.contentDescription = context.getString(R.string.settings)
        badgeView.setImageResource(R.drawable.weeboonime)

        // Bantu D-pad lokal; Leanback BrowseFrameLayout tetap perlu di-intercept di fragment.
        searchOrbView.nextFocusRightId = R.id.settings_orb
        settingsOrbView.nextFocusLeftId = R.id.title_orb

        clipToPadding = false
        clipChildren = false
        updateBadgeVisibility()
        post { ensureFullWidth() }
    }

    /**
     * Leanback [BrowseFrameLayout] mengarahkan FOCUS_RIGHT dari title ke konten utama.
     * Intersep agar Search → Settings / Settings → Search tetap jalan.
     */
    fun interceptFocusSearch(focused: View?, direction: Int): View? {
        if (focused == null) return null
        if (!hasSearchListener || !hasSettingsListener) return null
        if (searchOrbView.visibility != VISIBLE || settingsOrbView.visibility != VISIBLE) return null

        val onSearch = isWithin(searchOrbView, focused)
        val onSettings = isWithin(settingsOrbView, focused)

        if (onSearch && direction == FOCUS_RIGHT) {
            return settingsOrbView
        }
        if (onSettings && direction == FOCUS_LEFT) {
            return searchOrbView
        }
        return null
    }

    private fun isWithin(root: View, focused: View): Boolean {
        var current: View? = focused
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    fun setTitle(titleText: CharSequence?) {
        textView.text = titleText
        updateBadgeVisibility()
    }

    fun getTitle(): CharSequence? = textView.text

    fun setBadgeDrawable(drawable: Drawable?) {
        // Leanback mengirim null saat setTitle — jangan sampai wordmark hilang.
        badgeView.setImageResource(R.drawable.weeboonime)
        updateBadgeVisibility()
    }

    fun setUserBadgeColor(color: Int?) {
        if (color == null || textView.text.isNullOrBlank()) {
            textView.background = null
            return
        }
        val bg = (textView.background?.mutate() as? GradientDrawable)
            ?: GradientDrawable().also { textView.background = it }
        bg.setColor(color)
        bg.cornerRadius = 6f * resources.displayMetrics.density
    }

    fun getBadgeDrawable(): Drawable? = badgeView.drawable

    fun setOnSearchClickedListener(listener: OnClickListener?) {
        hasSearchListener = listener != null
        searchOrbView.setOnOrbClickedListener(listener)
        updateOrbVisibility()
    }

    fun setOnSettingsClickedListener(listener: OnClickListener?) {
        hasSettingsListener = listener != null
        settingsOrbView.setOnOrbClickedListener(listener)
        updateOrbVisibility()
    }

    fun getSearchAffordanceView(): View = searchOrbView

    fun setSearchAffordanceColors(colors: SearchOrbView.Colors) {
        searchOrbView.orbColors = colors
        settingsOrbView.orbColors = colors
    }

    fun getSearchAffordanceColors(): SearchOrbView.Colors = searchOrbView.orbColors

    fun enableAnimation(enable: Boolean) {
        searchOrbView.enableOrbColorAnimation(enable && searchOrbView.hasFocus())
        settingsOrbView.enableOrbColorAnimation(enable && settingsOrbView.hasFocus())
    }

    fun updateComponentsVisibility(flags: Int) {
        this.flags = flags
        if (flags and TitleViewAdapter.BRANDING_VIEW_VISIBLE == TitleViewAdapter.BRANDING_VIEW_VISIBLE) {
            updateBadgeVisibility()
        } else {
            badgeView.visibility = GONE
            textView.visibility = GONE
        }
        updateOrbVisibility()
    }

    private fun updateOrbVisibility() {
        val showOrbs =
            flags and TitleViewAdapter.SEARCH_VIEW_VISIBLE == TitleViewAdapter.SEARCH_VIEW_VISIBLE
        searchOrbView.visibility =
            if (hasSearchListener && showOrbs) VISIBLE else INVISIBLE
        settingsOrbView.visibility =
            if (hasSettingsListener && showOrbs) VISIBLE else INVISIBLE
    }

    private fun updateBadgeVisibility() {
        if (badgeView.drawable == null) {
            badgeView.setImageResource(R.drawable.weeboonime)
        }
        badgeView.visibility = VISIBLE
        textView.visibility = if (textView.text.isNullOrBlank()) GONE else VISIBLE
    }

    private fun ensureFullWidth() {
        val lp = layoutParams ?: return
        if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams = lp
        }
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = titleViewAdapter
}
