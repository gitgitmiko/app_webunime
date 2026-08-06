package com.webunime.tv.ui.browse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.VerticalGridView
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.CatalogSnapshot
import com.webunime.tv.ui.search.SearchActivity
import java.util.Calendar

/**
 * Browse: baris hero carousel (ikut scroll) + backdrop + baris katalog.
 */
class BrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()
    private val rowPaging = mutableMapOf<Long, RowPagingState>()

    private var hero: HeroCarouselController? = null
    private var heroRowIndex: Int = -1

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        if (view is ViewGroup) {
            view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        clearOpaqueBackgrounds(view)
        view.post { clearOpaqueBackgrounds(view) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        title = getString(R.string.browse_title)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        brandColor = Color.TRANSPARENT
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.wu_accent)

        hero = HeroCarouselController.attach(requireActivity()) { item ->
            openCatalog(item)
        }

        setOnSearchClickedListener {
            startActivity(Intent(requireActivity(), SearchActivity::class.java))
        }

        val cardRowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            shadowEnabled = true
            selectEffectEnabled = true
            headerPresenter = hideBlankHeaderPresenter()
        }
        val heroRowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
            shadowEnabled = false
            selectEffectEnabled = false
            headerPresenter = hideBlankHeaderPresenter()
        }
        val rowSelector = ClassPresenterSelector().apply {
            addClassPresenter(HeroListRow::class.java, heroRowPresenter)
            addClassPresenter(ListRow::class.java, cardRowPresenter)
        }
        rowsAdapter = ArrayObjectAdapter(rowSelector)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is HeroCarouselItem -> hero?.openCurrent()
                is CatalogItem -> openCatalog(item)
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, rowViewHolder, row ->
            updateHeroForSelection(item)
            if (selectedPosition >= 0) lastRowIndex = selectedPosition
            val listRow = row as? ListRow ?: return@OnItemViewSelectedListener
            if (listRow is HeroListRow) {
                lastItemIndex = 0
                return@OnItemViewSelectedListener
            }
            val selectedIndex =
                (rowViewHolder as? ListRowPresenter.ViewHolder)?.selectedPosition ?: -1
            if (selectedIndex >= 0) lastItemIndex = selectedIndex
            maybeLoadMore(listRow.headerItem.id, selectedIndex)
        }
    }

    override fun onPause() {
        pendingPositionRestore = true
        cancelPendingRestores()
        hero?.pauseRotate()
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
        hero?.release()
        hero = null
        super.onDestroy()
    }

    fun rowsGrid(): VerticalGridView? = rowsSupportFragment?.verticalGridView

    fun cancelPendingRestores() {
        view?.removeCallbacks(restoreSelectionRunnable)
        view?.removeCallbacks(focusGridRunnable)
        restoreRetries = 99
    }

    fun restoreRowFocus() {
        view?.removeCallbacks(focusGridRunnable)
        view?.post(focusGridRunnable)
    }

    private fun openCatalog(catalog: CatalogItem) {
        if (catalog.type == TYPE_CONTINUE) {
            (activity as? MainActivity)?.openContinueWatch(catalog)
            return
        }
        val slug = catalog.slug?.takeIf { it.isNotBlank() }
            ?: catalog.anime_slug?.takeIf { it.isNotBlank() }
            ?: return
        (activity as? MainActivity)?.openDetail(slug, catalog.episode)
    }

    private fun focusRowsGrid() {
        if (!isAdded) return
        val act = activity ?: return
        if (act.window.decorView.findFocus() != null) return
        val grid = rowsGrid() ?: return
        if ((adapter?.size() ?: 0) <= 0) return
        if (selectedPosition < 0) selectedPosition = 0
        grid.isFocusable = true
        grid.isFocusableInTouchMode = true
        grid.requestFocus()
    }

    private fun updateHeroForSelection(item: Any?) {
        val h = hero ?: return
        when (item) {
            is HeroCarouselItem -> h.resumeRotate()
            is CatalogItem -> {
                if (item.type == TYPE_CONTINUE) h.resumeRotate()
                else h.onBrowseItemFocused(item)
            }
            else -> h.resumeRotate()
        }
    }

    private fun hideBlankHeaderPresenter(): RowHeaderPresenter =
        object : RowHeaderPresenter() {
            override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
                super.onBindViewHolder(viewHolder, item)
                val header = (item as? HeaderItem) ?: (item as? ListRow)?.headerItem
                val name = header?.name
                viewHolder.view.visibility =
                    if (name.isNullOrBlank()) View.GONE else View.VISIBLE
                if (name.isNullOrBlank()) {
                    viewHolder.view.layoutParams = viewHolder.view.layoutParams?.apply {
                        height = 0
                    }
                }
            }
        }

    private fun clearOpaqueBackgrounds(root: View) {
        root.background = null
        root.setBackgroundColor(Color.TRANSPARENT)
        rowsSupportFragment?.view?.let {
            it.background = null
            it.setBackgroundColor(Color.TRANSPARENT)
        }
        rowsSupportFragment?.verticalGridView?.setBackgroundColor(Color.TRANSPARENT)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is ViewGroup) {
                    child.background = null
                    child.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
    }

    fun reloadRows() {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        val snap = (requireActivity().application as WebunimeApp).catalogRepository.snapshot
        val keepRow = lastRowIndex
        val keepItem = lastItemIndex
        rowsAdapter.clear()
        rowPaging.clear()
        continueRowIndex = -1
        heroRowIndex = -1

        fun addCardRow(title: String, items: List<CatalogItem>, isContinue: Boolean = false) {
            if (items.isEmpty()) return
            val list = ArrayObjectAdapter(cardPresenter)
            val rowId = rowsAdapter.size().toLong()
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

        fun addCardRow(titleRes: Int, items: List<CatalogItem>, isContinue: Boolean = false) =
            addCardRow(getString(titleRes), items, isContinue)

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val yearMovies = snap.movies.filter { it.tahun == currentYear.toString() }
        val familyMovies = snap.movies.filter { item ->
            item.genre.orEmpty().any { it.equals("Family", ignoreCase = true) }
        }
        val featured = buildFeaturedCarousel(snap)

        hero?.setFeatured(featured)
        if (featured.isNotEmpty()) {
            heroRowIndex = rowsAdapter.size()
            val heroAdapter = ArrayObjectAdapter(HeroPresenter { hero })
            heroAdapter.add(HeroCarouselItem())
            rowsAdapter.add(
                HeroListRow(HeaderItem(heroRowIndex.toLong(), ""), heroAdapter),
            )
        }

        addCardRow(R.string.row_continue, buildContinueItems(), isContinue = true)
        addCardRow(
            R.string.row_indonesia,
            snap.indonesia.sortedWith(
                compareByDescending<CatalogItem> { it.releaseSortKey() }
                    .thenByDescending { it.tahun?.toIntOrNull() ?: 0 }
                    .thenBy { it.displayTitle() }
            ),
        )
        addCardRow(getString(R.string.row_movies_year, currentYear), yearMovies)
        addCardRow(R.string.row_family, familyMovies)
        addCardRow(R.string.row_horror, snap.horror)
        addCardRow(R.string.row_series, snap.series)
        addCardRow(R.string.row_anime_latest, snap.animeLatest)
        addCardRow(R.string.row_anime, snap.anime)
        addCardRow(R.string.row_anime_movies, snap.animeMovies)

        rowsBuilt = rowsAdapter.size() > 0
        if (rowsBuilt) {
            lastRowIndex = keepRow.coerceIn(0, rowsAdapter.size() - 1)
            lastItemIndex = keepItem.coerceAtLeast(0)
            selectedPosition = lastRowIndex
            view?.let { clearOpaqueBackgrounds(it) }
            restoreRetries = 0
            view?.post(restoreSelectionRunnable)
        }
    }

    private fun buildFeaturedCarousel(snap: CatalogSnapshot): List<CatalogItem> {
        val pool = (snap.movies + snap.series + snap.indonesia + snap.horror)
            .asSequence()
            .filter { !isAnimeCatalog(it) }
            .filter { ratingValue(it) > FEATURED_MIN_RATING }
            .distinctBy { it.slug?.takeIf { s -> s.isNotBlank() } ?: it.displayTitle() }
            .toList()
        val withLandscape = pool
            .filter { !it.thumbnail_landscape.isNullOrBlank() }
            .sortedByDescending { ratingValue(it) }
        val ranked = withLandscape.ifEmpty {
            pool.sortedByDescending { ratingValue(it) }
        }
        return ranked.take(FEATURED_LIMIT)
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

    private fun refreshContinueRowOnly() {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        val items = buildContinueItems()
        if (continueRowIndex in 0 until rowsAdapter.size()) {
            val listRow = rowsAdapter.get(continueRowIndex) as? ListRow ?: return
            if (listRow is HeroListRow) return
            val adapter = listRow.adapter as? ArrayObjectAdapter ?: return
            adapter.clear()
            items.forEach { adapter.add(it) }
            val rowId = listRow.headerItem.id
            rowPaging[rowId] = RowPagingState(items, adapter, items.size)
            if (items.isEmpty()) {
                reloadRows()
            }
            return
        }
        if (items.isNotEmpty()) {
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

        val grid = rowsGrid() ?: return
        grid.setSelectedPosition(rowIndex)

        if (listRow is HeroListRow) {
            val holder = grid.findViewHolderForAdapterPosition(rowIndex) as? ListRowPresenter.ViewHolder
            val horizontal = holder?.gridView
            if (horizontal != null) {
                horizontal.selectedPosition = 0
                horizontal.requestFocus()
                restoreRetries = 0
            } else {
                restoreRetries++
                view?.removeCallbacks(restoreSelectionRunnable)
                view?.postDelayed(restoreSelectionRunnable, 100)
            }
            return
        }

        ensureLoadedUntil(listRow.headerItem.id, lastItemIndex)
        val itemIndex = lastItemIndex.coerceIn(0, (listRow.adapter?.size() ?: 1) - 1)
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
        private const val PAGE_SIZE = 10
        private const val PREFETCH_THRESHOLD = 3
        private const val FEATURED_LIMIT = 10
        private const val FEATURED_MIN_RATING = 7.0
        const val TYPE_CONTINUE = "continue"

        private fun isAnimeCatalog(item: CatalogItem): Boolean {
            val t = item.type?.lowercase().orEmpty()
            if (t.contains("anime")) return true
            if (!item.anime_slug.isNullOrBlank()) return true
            return false
        }

        private fun ratingValue(item: CatalogItem): Double {
            val raw = item.rating?.trim().orEmpty()
            if (raw.isEmpty()) return 0.0
            return raw.replace(',', '.')
                .replace(Regex("""[^\d.]"""), "")
                .toDoubleOrNull() ?: 0.0
        }

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
