package com.webunime.tv.ui.browse

import android.content.Intent
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
import android.view.ViewGroup
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
import androidx.leanback.widget.VerticalGridView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.search.SearchActivity
import java.util.Calendar

/**
 * Browse Netflix-style: background berubah mengikuti poster item yang sedang difokus.
 */
class BrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()
    private val rowPaging = mutableMapOf<Long, RowPagingState>()

    private var backgroundManager: BackgroundManager? = null
    private val bgHandler = Handler(Looper.getMainLooper())
    private var pendingThumb: String? = null
    private var lastThumb: String? = null

    /** Posisi browse yang dipertahankan saat kembali dari Detail/Player. */
    private var lastRowIndex: Int = 0
    private var lastItemIndex: Int = 0
    private var continueRowIndex: Int = -1
    private var rowsBuilt: Boolean = false
    /** True hanya sekali setelah kembali dari Detail/Player. */
    private var pendingPositionRestore: Boolean = false
    private var restoreRetries: Int = 0

    private val restoreSelectionRunnable = Runnable { restoreBrowseSelection() }
    private val focusGridRunnable = Runnable { focusRowsGrid() }

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
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        if (view is ViewGroup) {
            view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        view.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        title = getString(R.string.browse_title)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        brandColor = ContextCompat.getColor(requireContext(), R.color.wu_bg)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.wu_accent)

        prepareBackgroundManager()

        setOnSearchClickedListener {
            startActivity(Intent(requireActivity(), SearchActivity::class.java))
        }

        val rowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            shadowEnabled = true
            selectEffectEnabled = true
        }
        rowsAdapter = ArrayObjectAdapter(rowPresenter)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val catalog = item as? CatalogItem ?: return@OnItemViewClickedListener
            if (catalog.type == TYPE_CONTINUE) {
                (activity as? MainActivity)?.openContinueWatch(catalog)
                return@OnItemViewClickedListener
            }
            val slug = catalog.slug?.takeIf { it.isNotBlank() }
                ?: catalog.anime_slug?.takeIf { it.isNotBlank() }
                ?: return@OnItemViewClickedListener
            (activity as? MainActivity)?.openDetail(slug, catalog.episode)
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, rowViewHolder, row ->
            val catalog = item as? CatalogItem
            scheduleBackground(catalog?.thumbnail)
            val listRow = row as? ListRow ?: return@OnItemViewSelectedListener
            val selectedIndex =
                (rowViewHolder as? ListRowPresenter.ViewHolder)?.selectedPosition ?: -1
            if (selectedPosition >= 0) lastRowIndex = selectedPosition
            if (selectedIndex >= 0) lastItemIndex = selectedIndex
            maybeLoadMore(listRow.headerItem.id, selectedIndex)
        }

        // Katalog diisi MainActivity setelah fetch GitHub — jangan tampilkan lokal dulu.
    }

    override fun onPause() {
        // Akan kembali dari Detail/Player → pulihkan posisi sekali di onResume.
        pendingPositionRestore = true
        cancelPendingRestores()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!rowsBuilt) return
        refreshContinueRowOnly()
        if (pendingPositionRestore) {
            pendingPositionRestore = false
            restoreRetries = 0
            view?.post(restoreSelectionRunnable)
        }
    }

    override fun onDestroy() {
        cancelPendingRestores()
        bgHandler.removeCallbacks(applyBackground)
        backgroundManager = null
        super.onDestroy()
    }

    fun rowsGrid(): VerticalGridView? = rowsSupportFragment?.verticalGridView

    /** Batalkan restore tertunda — dipanggil saat user menekan D-pad. */
    fun cancelPendingRestores() {
        view?.removeCallbacks(restoreSelectionRunnable)
        view?.removeCallbacks(focusGridRunnable)
        restoreRetries = 99
    }

    /**
     * Pulihkan fokus ke grid hanya jika fokus benar-benar hilang.
     * Jangan merebut fokus dari tombol search / navigasi user.
     */
    fun restoreRowFocus() {
        view?.removeCallbacks(focusGridRunnable)
        view?.post(focusGridRunnable)
    }

    private fun focusRowsGrid() {
        if (!isAdded) return
        val act = activity ?: return
        // Ada fokus valid (kartu / search orb) → jangan ganggu.
        if (act.window.decorView.findFocus() != null) return
        val grid = rowsGrid() ?: return
        if ((adapter?.size() ?: 0) <= 0) return
        if (selectedPosition < 0) selectedPosition = 0
        grid.isFocusable = true
        grid.isFocusableInTouchMode = true
        grid.requestFocus()
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
                    backgroundManager?.drawable = dimmedBackground(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    /* no-op */
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (pendingThumb == url) setDefaultBackground()
                }
            })
    }

    private fun dimmedBackground(bitmap: Bitmap): Drawable {
        return LayerDrawable(
            arrayOf(
                BitmapDrawable(resources, bitmap),
                ColorDrawable(0xB3000000.toInt()),
            )
        )
    }

    fun reloadRows() {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        val snap = (requireActivity().application as WebunimeApp).catalogRepository.snapshot
        val keepRow = lastRowIndex
        val keepItem = lastItemIndex
        rowsAdapter.clear()
        rowPaging.clear()
        continueRowIndex = -1

        fun addRow(title: String, items: List<CatalogItem>, isContinue: Boolean = false) {
            if (items.isEmpty() && !isContinue) return
            if (items.isEmpty()) return
            val list = ArrayObjectAdapter(cardPresenter)
            val rowId = rowsAdapter.size().toLong()
            // Saat restore ke index jauh, load cukup item sampai posisi itu (+ buffer).
            val want = if (keepItem > 0 && title != getString(R.string.row_continue)) {
                (keepItem + PAGE_SIZE).coerceAtMost(items.size)
            } else {
                items.size.coerceAtMost(PAGE_SIZE)
            }.coerceAtLeast(1.coerceAtMost(items.size))
            val initialCount = want.coerceAtMost(items.size)
            for (i in 0 until initialCount) {
                list.add(items[i])
            }
            rowPaging[rowId] = RowPagingState(allItems = items, adapter = list, loadedCount = initialCount)
            if (isContinue) continueRowIndex = rowsAdapter.size()
            rowsAdapter.add(ListRow(HeaderItem(rowId, title), list))
        }

        fun addRow(titleRes: Int, items: List<CatalogItem>, isContinue: Boolean = false) =
            addRow(getString(titleRes), items, isContinue)

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val familyMovies = snap.movies.filter { item ->
            item.genre.orEmpty().any { it.equals("Family", ignoreCase = true) }
        }

        addRow(R.string.row_continue, buildContinueItems(), isContinue = true)
        addRow(
            getString(R.string.row_movies_year, currentYear),
            snap.movies.filter { it.tahun == currentYear.toString() }
        )
        addRow(R.string.row_family, familyMovies)
        addRow(R.string.row_horror, snap.horror)
        addRow(R.string.row_series, snap.series)
        addRow(R.string.row_anime_latest, snap.animeLatest)
        addRow(R.string.row_anime, snap.anime)
        addRow(R.string.row_anime_movies, snap.animeMovies)

            rowsBuilt = rowsAdapter.size() > 0
        if (rowsBuilt) {
            lastRowIndex = keepRow.coerceIn(0, rowsAdapter.size() - 1)
            lastItemIndex = keepItem.coerceAtLeast(0)
            selectedPosition = lastRowIndex
            // Restore posisi hanya lewat jalur pending (onResume setelah Detail),
            // atau sekali di sini saat cold rebuild katalog.
            restoreRetries = 0
            view?.post(restoreSelectionRunnable)
        }
    }

    private fun buildContinueItems(): List<CatalogItem> =
        (requireActivity().application as WebunimeApp)
            .watchSessions
            .continueWatching()
            .map { session ->
                CatalogItem(
                    type = TYPE_CONTINUE,
                    judul = session.title,
                    thumbnail = session.thumbnail,
                    slug = session.slug,
                    episode = session.episode,
                    durasi = formatContinueMeta(session.positionMs, session.durationMs),
                )
            }

    /** Update baris Lanjutkan tanpa mereset scroll baris lain. */
    private fun refreshContinueRowOnly() {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        val items = buildContinueItems()
        if (continueRowIndex in 0 until rowsAdapter.size()) {
            val listRow = rowsAdapter.get(continueRowIndex) as? ListRow ?: return
            val adapter = listRow.adapter as? ArrayObjectAdapter ?: return
            adapter.clear()
            items.forEach { adapter.add(it) }
            val rowId = listRow.headerItem.id
            rowPaging[rowId] = RowPagingState(items, adapter, items.size)
            if (items.isEmpty()) {
                // Hapus baris kosong — hanya bila perlu; rebuild penuh agar ID konsisten.
                reloadRows()
            }
            return
        }
        if (items.isNotEmpty()) {
            // Baru ada session lanjut → sisipkan baris tanpa kehilangan posisi relatif.
            reloadRows()
        }
    }

    private fun restoreBrowseSelection() {
        if (!isAdded || !this::rowsAdapter.isInitialized) return
        if (rowsAdapter.size() <= 0) return
        if (restoreRetries > 5) return

        val rowIndex = lastRowIndex.coerceIn(0, rowsAdapter.size() - 1)
        selectedPosition = rowIndex
        val listRow = rowsAdapter.get(rowIndex) as? ListRow ?: return
        ensureLoadedUntil(listRow.headerItem.id, lastItemIndex)
        val itemIndex = lastItemIndex.coerceIn(0, (listRow.adapter?.size() ?: 1) - 1)

        val grid = rowsGrid() ?: return
        grid.setSelectedPosition(rowIndex)
        val holder = grid.findViewHolderForAdapterPosition(rowIndex) as? ListRowPresenter.ViewHolder
        val horizontal = holder?.gridView
        if (horizontal != null) {
            horizontal.selectedPosition = itemIndex
            horizontal.requestFocus()
            restoreRetries = 0
        } else {
            restoreRetries++
            view?.removeCallbacks(restoreSelectionRunnable)
            view?.postDelayed(restoreSelectionRunnable, 100)
        }
    }

    private fun ensureLoadedUntil(rowId: Long, index: Int) {
        val state = rowPaging[rowId] ?: return
        while (state.loadedCount <= index && state.loadedCount < state.allItems.size) {
            appendNextPage(state)
        }
    }

    private fun maybeLoadMore(rowId: Long, selectedIndex: Int) {
        if (selectedIndex < 0) return
        val state = rowPaging[rowId] ?: return
        if (state.loadedCount >= state.allItems.size) return
        if (selectedIndex < state.loadedCount - PREFETCH_THRESHOLD) return
        appendNextPage(state)
    }

    private fun appendNextPage(state: RowPagingState) {
        val nextEnd = (state.loadedCount + PAGE_SIZE).coerceAtMost(state.allItems.size)
        if (nextEnd <= state.loadedCount) return
        for (i in state.loadedCount until nextEnd) {
            state.adapter.add(state.allItems[i])
        }
        state.loadedCount = nextEnd
    }

    private class RowPagingState(
        val allItems: List<CatalogItem>,
        val adapter: ArrayObjectAdapter,
        var loadedCount: Int,
    )

    companion object {
        private const val BACKGROUND_DELAY_MS = 350L
        private const val PAGE_SIZE = 10
        private const val PREFETCH_THRESHOLD = 3
        const val TYPE_CONTINUE = "continue"

        private fun formatContinueMeta(positionMs: Long, durationMs: Long): String {
            fun mmss(ms: Long): String {
                val totalSec = (ms / 1000).coerceAtLeast(0)
                val m = totalSec / 60
                val s = totalSec % 60
                return "%d:%02d".format(m, s)
            }
            return if (durationMs > 0) {
                "${mmss(positionMs)} / ${mmss(durationMs)}"
            } else {
                "Lanjut ${mmss(positionMs)}"
            }
        }
    }
}
