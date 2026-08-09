package com.webunime.tv.ui.browse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
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
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.CatalogSection
import com.webunime.tv.data.CatalogSnapshot
import com.webunime.tv.ui.search.SearchActivity
import com.webunime.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Browse: baris hero carousel (ikut scroll) + backdrop + baris katalog.
 */
class BrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()
    private val rowLoadingPresenter = RowLoadingPresenter()
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

    /** Spesifikasi baris yang belum ditampilkan (lazy vertikal). */
    private var deferredRowSpecs: List<DeferredRowSpec> = emptyList()
    private var deferredRowIndex: Int = 0
    private var appendRowsJob: Job? = null
    private var keepItemForDeferred: Int = 0

    private val restoreSelectionRunnable = Runnable { restoreBrowseSelection() }
    private val focusGridRunnable = Runnable { focusRowsGrid() }

    override fun onInflateTitleView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.wu_browse_title, parent, false)

    private fun bindSettingsOrb() {
        val title = titleView as? WebunimeTitleView ?: return
        title.setOnSettingsClickedListener {
            startActivity(Intent(requireActivity(), SettingsActivity::class.java))
        }
    }

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
        bindSettingsOrb()
        view?.post { bindSettingsOrb() }

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
                is RowLoadingItem -> Unit
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, rowViewHolder, row ->
            updateHeroForSelection(item)
            if (selectedPosition >= 0) lastRowIndex = selectedPosition
            val listRow = row as? ListRow ?: return@OnItemViewSelectedListener
            if (listRow is HeroListRow) {
                lastItemIndex = 0
                // Prefetch baris berikutnya saat masih di hero/continue
                maybeAppendDeferredRows()
                return@OnItemViewSelectedListener
            }
            val selectedIndex =
                (rowViewHolder as? ListRowPresenter.ViewHolder)?.selectedPosition ?: -1
            if (selectedIndex >= 0) lastItemIndex = selectedIndex
            maybeLoadMore(listRow.headerItem.id, selectedIndex)
            maybeAppendDeferredRows()
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
        if (pendingPositionRestore) {
            pendingPositionRestore = false
            restoreRetries = 0
            // Kembali dari Search/Detail: restore fokus sekali, baru soft-refresh continue.
            // Jangan reloadRows di sini — itu yang bikin bounce atas/bawah.
            view?.post {
                restoreBrowseSelection()
                view?.postDelayed({
                    if (isAdded) refreshContinueRowOnly(allowFullReload = false)
                }, 350)
            }
        } else {
            refreshContinueRowOnly(allowFullReload = true)
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
            ?: catalog.series_slug?.takeIf { it.isNotBlank() }
            ?: return
        (activity as? MainActivity)?.openDetail(slug, catalog.episode, catalog.season)
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
        val repo = (requireActivity().application as WebunimeApp).catalogRepository
        val snap = repo.snapshot
        val keepRow = lastRowIndex
        val keepItem = lastItemIndex
        keepItemForDeferred = keepItem
        appendRowsJob?.cancel()
        appendRowsJob = null

        rowsAdapter.clear()
        rowPaging.clear()
        continueRowIndex = -1
        heroRowIndex = -1
        deferredRowIndex = 0

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
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

        addCardRow(getString(R.string.row_continue), buildContinueItems(), isContinue = true)

        // Baris lain: lazy saat geser bawah (+ prefetch 1 baris).
        deferredRowSpecs = listOf(
            DeferredRowSpec(
                title = getString(R.string.row_indonesia),
                sections = listOf(CatalogSection.INDONESIA),
                items = { s ->
                    s.indonesia.sortedWith(
                        compareByDescending<CatalogItem> { it.releaseSortKey() }
                            .thenByDescending { it.tahun?.toIntOrNull() ?: 0 }
                            .thenBy { it.displayTitle() },
                    )
                },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_movies_year, currentYear),
                sections = listOf(CatalogSection.MOVIES),
                items = { s -> s.movies.filter { it.tahun == currentYear.toString() } },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_family),
                sections = listOf(CatalogSection.MOVIES),
                items = { s ->
                    s.movies.filter { item ->
                        item.genre.orEmpty().any { it.equals("Family", ignoreCase = true) }
                    }
                },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_horror),
                sections = listOf(CatalogSection.HORROR),
                items = { s -> s.horror },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_series_latest),
                sections = listOf(CatalogSection.SERIES_LATEST, CatalogSection.SERIES),
                items = { s -> s.seriesLatest },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_series),
                sections = listOf(CatalogSection.SERIES),
                items = { s -> s.series },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_anime_latest),
                sections = listOf(CatalogSection.ANIME_LATEST, CatalogSection.ANIME),
                items = { s -> s.animeLatest },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_anime),
                sections = listOf(CatalogSection.ANIME),
                items = { s -> s.anime },
            ),
            DeferredRowSpec(
                title = getString(R.string.row_anime_movies),
                sections = listOf(CatalogSection.ANIME_MOVIES),
                items = { s -> s.animeMovies },
            ),
        )

        rowsBuilt = rowsAdapter.size() > 0
        if (rowsBuilt) {
            lastRowIndex = keepRow.coerceIn(0, rowsAdapter.size() - 1)
            lastItemIndex = keepItem.coerceAtLeast(0)
            selectedPosition = lastRowIndex
            view?.let { clearOpaqueBackgrounds(it) }
            restoreRetries = 0
            view?.post(restoreSelectionRunnable)
            // Prefetch baris pertama di antrean (Indonesia) agar Down pertama terasa cepat.
            view?.post { maybeAppendDeferredRows(force = true) }
        }
    }

    private fun addCardRow(
        title: String,
        items: List<CatalogItem>,
        isContinue: Boolean = false,
        preferItemIndex: Int = 0,
    ) {
        if (items.isEmpty()) return
        val list = ArrayObjectAdapter(cardPresenter)
        val rowId = rowsAdapter.size().toLong()
        val want = if (preferItemIndex > 0 && !isContinue) {
            (preferItemIndex + PAGE_SIZE).coerceAtMost(items.size)
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

    /**
     * Append baris berikutnya jika fokus mendekati akhir, atau [force] (prefetch).
     * Series/Anime (~10MB) menampilkan baris loading + spinner saat JSON di-parse.
     */
    private fun maybeAppendDeferredRows(force: Boolean = false) {
        if (!isAdded || !this::rowsAdapter.isInitialized) return
        if (deferredRowIndex >= deferredRowSpecs.size) return
        if (appendRowsJob?.isActive == true) return

        val nearEnd = selectedPosition >= rowsAdapter.size() - ROW_PREFETCH
        if (!force && !nearEnd) return

        val repo = (requireActivity().application as WebunimeApp).catalogRepository
        val prefetchOnly = force
        appendRowsJob = viewLifecycleOwner.lifecycleScope.launch {
            var appended = 0
            val maxBatch = if (prefetchOnly) 1 else 2
            while (
                deferredRowIndex < deferredRowSpecs.size &&
                appended < maxBatch
            ) {
                if (!prefetchOnly && selectedPosition < rowsAdapter.size() - ROW_PREFETCH && appended > 0) {
                    break
                }
                val spec = deferredRowSpecs[deferredRowIndex]
                deferredRowIndex++

                val needsParse = spec.sections.any { !repo.isSectionLoaded(it) }
                var loadingRowPos = -1
                if (needsParse) {
                    loadingRowPos = rowsAdapter.size()
                    addLoadingRow(spec.title)
                }

                runCatching { repo.ensureSections(spec.sections) }
                if (!isAdded) return@launch

                if (loadingRowPos >= 0) {
                    removeLoadingRowAt(loadingRowPos)
                }

                val items = spec.items(repo.snapshot)
                if (items.isEmpty()) continue
                addCardRow(spec.title, items, preferItemIndex = keepItemForDeferred)
                appended++
                if (prefetchOnly) break
            }
        }
    }

    private fun addLoadingRow(title: String) {
        val list = ArrayObjectAdapter(rowLoadingPresenter)
        // Beberapa kartu skeleton supaya baris terasa penuh saat parse.
        val msg = getString(R.string.loading_row, title)
        repeat(4) { list.add(RowLoadingItem(msg)) }
        val rowId = rowsAdapter.size().toLong()
        rowsAdapter.add(ListRow(HeaderItem(rowId, title), list))
    }

    private fun removeLoadingRowAt(index: Int) {
        if (index !in 0 until rowsAdapter.size()) return
        val row = rowsAdapter.get(index) as? ListRow ?: return
        val first = row.adapter?.get(0)
        if (first is RowLoadingItem) {
            rowsAdapter.removeItems(index, 1)
        }
    }

    private fun buildFeaturedCarousel(snap: CatalogSnapshot): List<CatalogItem> {
        val pool = (snap.movies + snap.series + snap.indonesia + snap.horror)
            .asSequence()
            .filter { !isAnimeCatalog(it) }
            .filter { ratingValue(it) > FEATURED_MIN_RATING }
            .distinctBy { it.slug?.takeIf { s -> s.isNotBlank() } ?: it.displayTitle() }
            .toList()
        val withLandscape = pool.filter { !it.thumbnail_landscape.isNullOrBlank() }
        val source = withLandscape.ifEmpty { pool }
        // Acak tiap buka/reload browse; tetap rating > 7.
        return source.shuffled().take(FEATURED_LIMIT)
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

    private fun refreshContinueRowOnly(allowFullReload: Boolean = true) {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        val items = buildContinueItems()
        if (continueRowIndex in 0 until rowsAdapter.size()) {
            val listRow = rowsAdapter.get(continueRowIndex) as? ListRow ?: return
            if (listRow is HeroListRow) return
            val adapter = listRow.adapter as? ArrayObjectAdapter ?: return
            // Hindari clear+re-add jika isi sama — itu mencuri fokus / memicu bounce.
            if (continueItemsEqual(adapter, items)) return
            adapter.clear()
            items.forEach { adapter.add(it) }
            val rowId = listRow.headerItem.id
            rowPaging[rowId] = RowPagingState(items, adapter, items.size)
            if (items.isEmpty() && allowFullReload) {
                reloadRows()
            }
            return
        }
        if (items.isNotEmpty() && allowFullReload) {
            reloadRows()
        }
    }

    private fun continueItemsEqual(adapter: ArrayObjectAdapter, items: List<CatalogItem>): Boolean {
        if (adapter.size() != items.size) return false
        for (i in items.indices) {
            val a = adapter.get(i) as? CatalogItem ?: return false
            val b = items[i]
            if (a.slug != b.slug || a.episode != b.episode || a.durasi != b.durasi) return false
        }
        return true
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
                if (!grid.hasFocus() && !horizontal.hasFocus()) {
                    horizontal.requestFocus()
                }
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
            // Jangan requestFocus berulang jika sudah ada fokus di browse —
            // itu memicu resize kartu landscape/portrait → glitch atas/bawah.
            if (!grid.hasFocus() && !horizontal.hasFocus()) {
                horizontal.requestFocus()
            }
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

    private data class DeferredRowSpec(
        val title: String,
        val sections: List<CatalogSection>,
        val items: (CatalogSnapshot) -> List<CatalogItem>,
    )

    companion object {
        private const val PAGE_SIZE = 10
        private const val PREFETCH_THRESHOLD = 3
        /** Prefetch baris vertikal saat fokus mendekati N baris dari akhir. */
        private const val ROW_PREFETCH = 2
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
