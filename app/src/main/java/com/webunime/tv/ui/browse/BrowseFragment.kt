package com.webunime.tv.ui.browse

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.search.SearchActivity
import android.content.Intent

/**
 * Browse Netflix-style: background berubah mengikuti poster item yang sedang difokus.
 */
class BrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()

    private var backgroundManager: BackgroundManager? = null
    private val bgHandler = Handler(Looper.getMainLooper())
    private var pendingThumb: String? = null
    private var lastThumb: String? = null

    private val applyBackground = Runnable {
        val url = pendingThumb
        if (url.isNullOrBlank()) {
            setDefaultBackground()
            return@Runnable
        }
        if (url == lastThumb) return@Runnable
        lastThumb = url
        loadBackground(url)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        // Transparan agar BackgroundManager terlihat
        view.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        title = getString(R.string.browse_title)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.wu_bg)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.wu_accent)

        prepareBackgroundManager()

        setOnSearchClickedListener {
            startActivity(Intent(requireActivity(), SearchActivity::class.java))
        }

        val rowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM).apply {
            shadowEnabled = true
            selectEffectEnabled = true
        }
        rowsAdapter = ArrayObjectAdapter(rowPresenter)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val catalog = item as? CatalogItem ?: return@OnItemViewClickedListener
            val slug = catalog.slug?.takeIf { it.isNotBlank() }
                ?: catalog.anime_slug?.takeIf { it.isNotBlank() }
                ?: return@OnItemViewClickedListener
            (activity as? MainActivity)?.openDetail(slug, catalog.episode)
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            val catalog = item as? CatalogItem
            scheduleBackground(catalog?.thumbnail)
        }

        reloadRows()
    }

    override fun onResume() {
        super.onResume()
        restoreRowFocus()
    }

    override fun onDestroy() {
        bgHandler.removeCallbacks(applyBackground)
        backgroundManager = null
        super.onDestroy()
    }

    private fun prepareBackgroundManager() {
        val act = requireActivity()
        val mgr = BackgroundManager.getInstance(act).also { backgroundManager = it }
        if (!mgr.isAttached) {
            mgr.attach(act.window)
        }
        setDefaultBackground()
    }

    private fun scheduleBackground(thumbUrl: String?) {
        pendingThumb = thumbUrl
        bgHandler.removeCallbacks(applyBackground)
        bgHandler.postDelayed(applyBackground, BACKGROUND_DELAY_MS)
    }

    private fun setDefaultBackground() {
        lastThumb = null
        backgroundManager?.color = ContextCompat.getColor(requireContext(), R.color.wu_bg)
    }

    private fun loadBackground(url: String) {
        if (!isAdded) return
        val ctx = context ?: return
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.coerceAtLeast(1280)
        val h = metrics.heightPixels.coerceAtLeast(720)

        Glide.with(this)
            .asBitmap()
            .load(url)
            .centerCrop()
            .into(object : CustomTarget<Bitmap>(w, h) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (!isAdded || pendingThumb != url) return
                    val dimmed = dimmedBackground(resource)
                    backgroundManager?.drawable = dimmed
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    /* no-op */
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (pendingThumb == url) setDefaultBackground()
                }
            })
    }

    /** Poster + overlay gelap agar baris katalog tetap terbaca. */
    private fun dimmedBackground(bitmap: Bitmap): Drawable {
        return LayerDrawable(
            arrayOf(
                BitmapDrawable(resources, bitmap),
                ColorDrawable(0xB3000000.toInt()),
            )
        )
    }

    fun restoreRowFocus() {
        view?.post {
            if (!isAdded) return@post
            val grid = rowsSupportFragment?.verticalGridView
            if (grid != null) {
                grid.requestFocus()
                if (grid.selectedPosition < 0 && (adapter?.size() ?: 0) > 0) {
                    selectedPosition = 0
                }
            } else {
                view?.requestFocus()
            }
        }
    }

    fun reloadRows() {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        val snap = (requireActivity().application as WebunimeApp).catalogRepository.snapshot
        val previous = selectedPosition
        rowsAdapter.clear()

        fun addRow(titleRes: Int, items: List<CatalogItem>) {
            if (items.isEmpty()) return
            val list = ArrayObjectAdapter(cardPresenter)
            items.take(40).forEach { list.add(it) }
            rowsAdapter.add(
                ListRow(HeaderItem(rowsAdapter.size().toLong(), getString(titleRes)), list)
            )
        }

        addRow(R.string.row_movies, snap.movies)
        addRow(R.string.row_series, snap.series)
        addRow(R.string.row_anime_latest, snap.animeLatest)
        addRow(R.string.row_anime, snap.anime)
        addRow(R.string.row_anime_movies, snap.animeMovies)
        addRow(R.string.row_horror, snap.horror)

        if (rowsAdapter.size() > 0) {
            val target = previous.coerceIn(0, rowsAdapter.size() - 1)
            selectedPosition = target
            view?.post {
                if (!isAdded) return@post
                selectedPosition = target
                rowsSupportFragment?.verticalGridView?.requestFocus()
                    ?: view?.requestFocus()
            }
        }
    }

    companion object {
        private const val BACKGROUND_DELAY_MS = 350L
    }
}
