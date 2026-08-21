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
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.api.CatalogPage
import com.webunime.tv.ui.search.SearchActivity
import com.webunime.tv.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browse: baris hero carousel (ikut scroll) + backdrop + baris katalog.
 */
class BrowseFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter { item -> promptRemoveLibraryItem(item) }
    private val rowLoadingPresenter = RowLoadingPresenter()
    private val rowPaging = mutableMapOf<Long, RowPagingState>()

    private var hero: HeroCarouselController? = null
    private var heroRowIndex: Int = -1

    /** Posisi browse yang dipertahankan saat kembali dari Detail/Player. */
    private var lastRowIndex: Int = 0
    private var lastItemIndex: Int = 0
    private var continueRowIndex: Int = -1
    private var favoritesRowIndex: Int = -1
    private var rowsBuilt: Boolean = false
    /** True hanya sekali setelah kembali dari Detail/Player/Search/Settings. */
    private var pendingPositionRestore: Boolean = false
    private var restoreRetries: Int = 0
    /** Blok append baris lazy saat resume — append memicu scroll/shake. */
    private var suppressDeferredAppend: Boolean = false

    /** Spesifikasi baris yang belum ditampilkan (lazy vertikal). */
    private var deferredRowSpecs: List<DeferredRowSpec> = emptyList()
    private var deferredRowIndex: Int = 0
    private var nextRowId: Long = 1L
    private var appendRowsJob: Job? = null
    private var keepItemForDeferred: Int = 0

    private val restoreSelectionRunnable = Runnable { softRestoreFocusOnly() }
    private val focusGridRunnable = Runnable { focusRowsGrid() }
    private val endSuppressDeferredRunnable = Runnable { suppressDeferredAppend = false }

    override fun onInflateTitleView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.wu_browse_title, parent, false)

    /** Warna badge nama user, acak sekali per sesi buka app. */
    private var sessionUserBadgeColor: Int? = null

    private fun bindUserTitle() {
        if (!isAdded) return
        val label = (requireActivity().application as WebunimeApp)
            .authRepository
            .currentUser()
            ?.displayLabel()
            .orEmpty()
        if (sessionUserBadgeColor == null) {
            sessionUserBadgeColor = UserBadges.randomColor()
        }
        title = label
        (titleView as? WebunimeTitleView)?.setUserBadgeColor(
            if (label.isBlank()) null else sessionUserBadgeColor,
        )
    }

    private fun bindSettingsOrb() {
        val title = titleView as? WebunimeTitleView ?: return
        title.setOnSettingsClickedListener {
            startActivity(Intent(requireActivity(), SettingsActivity::class.java))
        }
        installTitleOrbFocusFix()
    }

    /**
     * Leanback selalu mengarahkan panah kanan dari title ke baris konten.
     * Bungkus listener supaya Search ↔ Settings tetap bisa digeser.
     */
    private fun installTitleOrbFocusFix() {
        if (!isAdded) return
        val root = view ?: return
        val frame = root.findViewById<BrowseFrameLayout>(androidx.leanback.R.id.browse_frame)
            ?: return
        if (frame.getTag(R.id.tag_orb_focus_fixed) == true) return
        val title = titleView as? WebunimeTitleView ?: return
        val previous = frame.onFocusSearchListener
        frame.setTag(R.id.tag_orb_focus_fixed, true)
        frame.setOnFocusSearchListener { focused, direction ->
            title.interceptFocusSearch(focused, direction)
                ?: previous?.onFocusSearch(focused, direction)
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
        view.post {
            clearOpaqueBackgrounds(view)
            installTitleOrbFocusFix()
            configureRowsGridStability()
        }
    }

    /** Kurangi realign otomatis Leanback yang terasa seperti beranda bergoyang. */
    private fun configureRowsGridStability() {
        val grid = rowsGrid() ?: return
        grid.windowAlignment = VerticalGridView.WINDOW_ALIGN_NO_EDGE
        grid.itemAlignmentOffsetPercent = VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED
        grid.isFocusDrawingOrderEnabled = true
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        bindUserTitle()
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

        val cardRowPresenter = object : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_XSMALL) {
            override fun initializeRowViewHolder(vh: RowPresenter.ViewHolder) {
                super.initializeRowViewHolder(vh)
                val listVh = vh as? ListRowPresenter.ViewHolder ?: return
                CardPresenter.styleCatalogRow(listVh.gridView)
            }
        }.apply {
            shadowEnabled = true
            selectEffectEnabled = true
            headerPresenter = hideBlankHeaderPresenter()
        }
        val heroRowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
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
                drainDeferredRows()
                return@OnItemViewSelectedListener
            }
            val selectedIndex =
                (rowViewHolder as? ListRowPresenter.ViewHolder)?.selectedPosition ?: -1
            if (selectedIndex >= 0) lastItemIndex = selectedIndex
            maybeLoadMore(listRow.headerItem.id, selectedIndex)
            drainDeferredRows()
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
        bindUserTitle()
        if (!rowsBuilt) return
        if (pendingPositionRestore) {
            pendingPositionRestore = false
            restoreRetries = 0
            // Jangan setSelectedPosition ulang — itu penyebab shake saat back.
            // Cukup pastikan fokus ada di grid jika benar-benar hilang.
            suppressDeferredAppend = true
            view?.removeCallbacks(endSuppressDeferredRunnable)
            viewLifecycleOwner.lifecycleScope.launch {
                val lib = (requireActivity().application as WebunimeApp).libraryRepository
                runCatching { lib.refresh() }
                if (isAdded) {
                    refreshContinueRowOnly(allowFullReload = false)
                    refreshFavoritesRowOnly(allowFullReload = false)
                }
                delay(1_800)
                if (isAdded) refreshContinueRowOnly(allowFullReload = false)
            }
            view?.post {
                if (isAdded) softRestoreFocusOnly()
            }
            view?.postDelayed(endSuppressDeferredRunnable, 600)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { (requireActivity().application as WebunimeApp).libraryRepository.refresh() }
                if (isAdded) {
                    refreshContinueRowOnly(allowFullReload = true)
                    refreshFavoritesRowOnly(allowFullReload = true)
                }
            }
        }
    }

    override fun onDestroy() {
        cancelPendingRestores()
        view?.removeCallbacks(endSuppressDeferredRunnable)
        hero?.release()
        hero = null
        super.onDestroy()
    }

    fun rowsGrid(): VerticalGridView? = rowsSupportFragment?.verticalGridView

    fun cancelPendingRestores() {
        view?.removeCallbacks(restoreSelectionRunnable)
        view?.removeCallbacks(focusGridRunnable)
        view?.removeCallbacks(endSuppressDeferredRunnable)
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
        val slug = catalog.detailSlug().takeIf { it.isNotBlank() } ?: return
        (activity as? MainActivity)?.openDetail(slug, catalog.episode, catalog.season, catalog.detailCollection())
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
            is CatalogItem -> h.onBrowseItemFocused(item)
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
        val keepRow = lastRowIndex
        val keepItem = lastItemIndex
        keepItemForDeferred = keepItem
        appendRowsJob?.cancel()
        appendRowsJob = null

        rowsAdapter.clear()
        rowPaging.clear()
        continueRowIndex = -1
        favoritesRowIndex = -1
        heroRowIndex = -1
        deferredRowIndex = 0
        nextRowId = 1L

        val featured = repo.heroItems.take(HERO_LIMIT)
        hero?.setFeatured(featured)
        if (featured.isNotEmpty()) {
            heroRowIndex = rowsAdapter.size()
            val heroAdapter = ArrayObjectAdapter(HeroPresenter { hero })
            heroAdapter.add(HeroCarouselItem())
            rowsAdapter.add(
                HeroListRow(HeaderItem(heroRowIndex.toLong(), ""), heroAdapter),
            )
        }

        addLocalCardRow(getString(R.string.row_continue), buildContinueItems(), isContinue = true)
        addLocalCardRow(getString(R.string.row_favorites), buildFavoriteItems(), isFavorites = true)

        deferredRowSpecs = listOf(
            DeferredRowSpec(getString(R.string.row_movies), "movies"),
            DeferredRowSpec(getString(R.string.row_action), "movies", genre = "Action,Adventure,Thriller"),
            DeferredRowSpec(getString(R.string.row_drama), "movies", genre = "Drama,Romance"),
            DeferredRowSpec(getString(R.string.row_horror), "horror"),
            DeferredRowSpec(getString(R.string.row_series_latest), "series-latest"),
            DeferredRowSpec(getString(R.string.row_series), "series"),
            DeferredRowSpec(getString(R.string.row_indonesia), "indonesia"),
            DeferredRowSpec(getString(R.string.row_anime_latest), "anime-latest"),
            DeferredRowSpec(getString(R.string.row_anime_top), "anime", sort = "rating"),
            DeferredRowSpec(getString(R.string.row_anime_hot), "anime", sort = "hot"),
            DeferredRowSpec(getString(R.string.row_anime), "anime"),
            DeferredRowSpec(getString(R.string.row_anime_movies), "anime-movies"),
        )

        rowsBuilt = rowsAdapter.size() > 0
        if (rowsBuilt) {
            lastRowIndex = keepRow.coerceIn(0, rowsAdapter.size() - 1)
            lastItemIndex = keepItem.coerceAtLeast(0)
            selectedPosition = lastRowIndex
            view?.let { clearOpaqueBackgrounds(it) }
            restoreRetries = 0
            view?.post(restoreSelectionRunnable)
            view?.post { drainDeferredRows() }
        }
    }

    private fun addLocalCardRow(
        title: String,
        items: List<CatalogItem>,
        isContinue: Boolean = false,
        isFavorites: Boolean = false,
    ) {
        if (items.isEmpty()) return
        val list = ArrayObjectAdapter(cardPresenter)
        items.forEach { list.add(it) }
        val rowId = allocRowId()
        rowPaging[rowId] = RowPagingState(
            allItems = items.toMutableList(),
            adapter = list,
            loadedCount = items.size,
            collection = "",
        )
        if (isContinue) continueRowIndex = rowsAdapter.size()
        if (isFavorites) favoritesRowIndex = rowsAdapter.size()
        rowsAdapter.add(ListRow(HeaderItem(rowId, title), list))
        items.forEach { CardPresenter.preload(requireContext(), it.thumbnail) }
    }

    private fun addApiCardRow(spec: DeferredRowSpec, page: CatalogPage) {
        if (page.items.isEmpty()) return
        val list = ArrayObjectAdapter(cardPresenter)
        page.items.forEach { list.add(it) }
        val rowId = allocRowId()
        rowPaging[rowId] = RowPagingState(
            allItems = page.items.toMutableList(),
            adapter = list,
            loadedCount = page.items.size,
            collection = spec.collection,
            genre = spec.genre,
            sort = spec.sort,
            page = page.page,
            total = page.total,
        )
        rowsAdapter.add(ListRow(HeaderItem(rowId, spec.title), list))
        page.items.forEach { CardPresenter.preload(requireContext(), it.thumbnail) }
    }

    private fun allocRowId(): Long = nextRowId++

    /**
     * Muat sisa baris katalog di latar belakang (2 per batch),
     * tidak menunggu user sampai ke bawah.
     */
    private fun drainDeferredRows() {
        if (!isAdded || !this::rowsAdapter.isInitialized) return
        if (deferredRowIndex >= deferredRowSpecs.size) return
        if (appendRowsJob?.isActive == true) return

        val repo = (requireActivity().application as WebunimeApp).catalogRepository
        appendRowsJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && deferredRowIndex < deferredRowSpecs.size) {
                while (suppressDeferredAppend) {
                    kotlinx.coroutines.delay(120)
                    if (!isAdded) return@launch
                }
                val batch = minOf(2, deferredRowSpecs.size - deferredRowIndex)
                repeat(batch) {
                    if (!isAdded || deferredRowIndex >= deferredRowSpecs.size) return@repeat
                    val spec = deferredRowSpecs[deferredRowIndex]
                    deferredRowIndex++
                    val loadingRowPos = rowsAdapter.size()
                    addLoadingRow(spec.title)
                    val page = runCatching {
                        repo.listCollectionPage(
                            collection = spec.collection,
                            page = 1,
                            genre = spec.genre,
                            sort = spec.sort,
                        )
                    }.getOrNull()
                    if (!isAdded) return@launch
                    removeLoadingRowAt(loadingRowPos)
                    if (page != null && page.items.isNotEmpty()) {
                        addApiCardRow(spec, page)
                    }
                }
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

    private fun buildContinueItems(): List<CatalogItem> {
        val app = requireActivity().application as WebunimeApp
        val fromApi = app.libraryRepository.history.take(20).map { entry ->
            val local = app.watchSessions.all()
                .firstOrNull { it.slug.equals(entry.slug, true) && !it.isFinished() }
            val durasi = local?.let { formatContinueMeta(it.positionMs, it.durationMs) }
                ?: entry.toCatalogItem().durasi
            entry.toCatalogItem(TYPE_CONTINUE).copy(
                episode = local?.episode ?: entry.resolvedEpisodeNum(),
                episode_source = entry.episodeSlug ?: local?.episodeSlug,
                thumbnail = entry.thumbnail ?: local?.thumbnail,
                durasi = durasi,
            )
        }
        val seen = fromApi.mapNotNull { it.slug?.lowercase() }.toMutableSet()
        val fromLocal = app.watchSessions.continueWatching().mapNotNull { session ->
            val key = session.slug.lowercase()
            if (key in seen) return@mapNotNull null
            seen.add(key)
            CatalogItem(
                type = TYPE_CONTINUE,
                judul = session.title,
                thumbnail = session.thumbnail,
                slug = session.slug,
                catalog = session.collection,
                episode = session.episode,
                episode_source = session.episodeSlug,
                durasi = formatContinueMeta(session.positionMs, session.durationMs),
            )
        }
        return (fromApi + fromLocal).take(20)
    }

    private fun buildFavoriteItems(): List<CatalogItem> =
        (requireActivity().application as WebunimeApp)
            .libraryRepository
            .favorites
            .map { it.toCatalogItem(TYPE_FAVORITE) }

    private fun promptRemoveLibraryItem(item: CatalogItem): Boolean {
        val kind = when (item.type) {
            TYPE_CONTINUE -> LibraryRowKind.CONTINUE
            TYPE_FAVORITE -> LibraryRowKind.FAVORITES
            else -> return false
        }
        if (!isAdded) return false
        val title = item.displayTitle()
        val message = if (kind == LibraryRowKind.CONTINUE) {
            getString(R.string.library_remove_continue, title)
        } else {
            getString(R.string.library_remove_favorite, title)
        }
        val themed = android.view.ContextThemeWrapper(requireContext(), R.style.Theme_WebunimeTv_AlertDialog)
        val dialog = android.app.AlertDialog.Builder(themed)
            .setMessage(message)
            .setPositiveButton(R.string.library_remove_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    removeLibraryItem(kind, item)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            styleTvDialogButtons(dialog)
            // Default fokus di Batal agar OK tidak langsung menghapus.
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
        return true
    }

    private fun styleTvDialogButtons(dialog: android.app.AlertDialog) {
        val buttons = listOf(
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE),
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE),
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL),
        )
        for (btn in buttons) {
            if (btn == null) continue
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
            btn.setBackgroundResource(R.drawable.bg_dialog_button)
            btn.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_button_text))
            btn.setAllCaps(false)
            btn.textSize = 16f
            btn.setTypeface(btn.typeface, android.graphics.Typeface.BOLD)
            val padH = (18 * resources.displayMetrics.density).toInt()
            val padV = (10 * resources.displayMetrics.density).toInt()
            btn.setPadding(padH, padV, padH, padV)
            btn.minHeight = (48 * resources.displayMetrics.density).toInt()
            btn.minWidth = (120 * resources.displayMetrics.density).toInt()
        }
    }

    private suspend fun removeLibraryItem(kind: LibraryRowKind, item: CatalogItem) {
        val app = requireActivity().application as WebunimeApp
        val col = item.detailCollection()
        val slug = item.detailSlug()
        if (slug.isBlank()) return
        runCatching {
            when (kind) {
                LibraryRowKind.CONTINUE -> {
                    app.libraryRepository.removeHistory(col, slug)
                    app.watchSessions.remove(slug, item.episode)
                }
                LibraryRowKind.FAVORITES -> app.libraryRepository.removeFavorite(col, slug)
            }
        }
        if (isAdded) {
            refreshContinueRowOnly(allowFullReload = true)
            refreshFavoritesRowOnly(allowFullReload = true)
        }
    }

    private fun refreshContinueRowOnly(allowFullReload: Boolean = true) {
        refreshLibraryRow(
            kind = LibraryRowKind.CONTINUE,
            items = buildContinueItems(),
            allowRemoveEmpty = allowFullReload,
        )
    }

    private fun refreshFavoritesRowOnly(allowFullReload: Boolean = true) {
        refreshLibraryRow(
            kind = LibraryRowKind.FAVORITES,
            items = buildFavoriteItems(),
            allowRemoveEmpty = allowFullReload,
        )
    }

    private fun refreshLibraryRow(
        kind: LibraryRowKind,
        items: List<CatalogItem>,
        allowRemoveEmpty: Boolean,
    ) {
        if (!this::rowsAdapter.isInitialized || !isAdded) return
        var index = when (kind) {
            LibraryRowKind.CONTINUE -> continueRowIndex
            LibraryRowKind.FAVORITES -> favoritesRowIndex
        }
        if (index in 0 until rowsAdapter.size()) {
            val listRow = rowsAdapter.get(index) as? ListRow ?: return
            if (listRow is HeroListRow) return
            val adapter = listRow.adapter as? ArrayObjectAdapter ?: return
            if (items.isEmpty()) {
                if (allowRemoveEmpty) {
                    rowsAdapter.removeItems(index, 1)
                    onLibraryRowRemoved(kind, index)
                }
                return
            }
            if (continueItemsEqual(adapter, items)) return
            adapter.clear()
            items.forEach { adapter.add(it) }
            val rowId = listRow.headerItem.id
            rowPaging[rowId] = RowPagingState(
                allItems = items.toMutableList(),
                adapter = adapter,
                loadedCount = items.size,
                collection = "",
            )
            items.forEach { CardPresenter.preload(requireContext(), it.thumbnail) }
            return
        }
        if (items.isNotEmpty()) {
            insertLibraryRow(kind, items)
        }
    }

    private fun insertLibraryRow(kind: LibraryRowKind, items: List<CatalogItem>) {
        val at = insertIndexFor(kind).coerceIn(0, rowsAdapter.size())
        val title = getString(
            if (kind == LibraryRowKind.CONTINUE) R.string.row_continue else R.string.row_favorites,
        )
        val list = ArrayObjectAdapter(cardPresenter)
        items.forEach { list.add(it) }
        val rowId = allocRowId()
        rowPaging[rowId] = RowPagingState(
            allItems = items.toMutableList(),
            adapter = list,
            loadedCount = items.size,
            collection = "",
        )
        rowsAdapter.add(at, ListRow(HeaderItem(rowId, title), list))
        shiftLibraryIndicesOnInsert(at)
        when (kind) {
            LibraryRowKind.CONTINUE -> continueRowIndex = at
            LibraryRowKind.FAVORITES -> favoritesRowIndex = at
        }
        items.forEach { CardPresenter.preload(requireContext(), it.thumbnail) }
    }

    private fun insertIndexFor(kind: LibraryRowKind): Int {
        val afterHero = if (heroRowIndex >= 0) heroRowIndex + 1 else 0
        return when (kind) {
            LibraryRowKind.CONTINUE -> afterHero
            LibraryRowKind.FAVORITES ->
                if (continueRowIndex >= 0) continueRowIndex + 1 else afterHero
        }
    }

    private fun shiftLibraryIndicesOnInsert(at: Int) {
        if (heroRowIndex >= at) heroRowIndex += 1
        if (continueRowIndex >= at) continueRowIndex += 1
        if (favoritesRowIndex >= at) favoritesRowIndex += 1
    }

    private fun onLibraryRowRemoved(kind: LibraryRowKind, removedAt: Int) {
        when (kind) {
            LibraryRowKind.CONTINUE -> continueRowIndex = -1
            LibraryRowKind.FAVORITES -> favoritesRowIndex = -1
        }
        if (heroRowIndex > removedAt) heroRowIndex -= 1
        if (continueRowIndex > removedAt) continueRowIndex -= 1
        if (favoritesRowIndex > removedAt) favoritesRowIndex -= 1
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

    /**
     * Kembalikan fokus ke baris browse tanpa mengubah selectedPosition
     * (menghindari animasi scroll Leanback = shaking).
     */
    private fun softRestoreFocusOnly() {
        if (!isAdded || !this::rowsAdapter.isInitialized) return
        if (rowsAdapter.size() <= 0) return

        val grid = rowsGrid() ?: return
        configureRowsGridStability()

        val focused = activity?.window?.decorView?.findFocus()
        if (focused != null && isUnderRowsGrid(focused, grid)) {
            restoreRetries = 0
            return
        }

        // Fokus di title Search/Settings setelah back dari Settings — biarkan di title,
        // jangan tarik ke konten (itu juga terasa goyang).
        if (focused != null && isUnderTitleView(focused)) {
            restoreRetries = 0
            return
        }

        val rowIndex = grid.selectedPosition.coerceIn(0, rowsAdapter.size() - 1)
        val holder = grid.findViewHolderForAdapterPosition(rowIndex) as? ListRowPresenter.ViewHolder
        val horizontal = holder?.gridView
        if (horizontal != null) {
            if (!horizontal.hasFocus()) horizontal.requestFocus()
            restoreRetries = 0
            return
        }

        if (!grid.hasFocus()) {
            grid.requestFocus()
        }
        // Holder belum siap — coba sekali lagi tanpa mengubah posisi.
        if (restoreRetries < 3) {
            restoreRetries++
            view?.removeCallbacks(restoreSelectionRunnable)
            view?.postDelayed(restoreSelectionRunnable, 50)
        } else {
            restoreRetries = 0
        }
    }

    private fun isUnderRowsGrid(focused: View, grid: VerticalGridView): Boolean {
        var v: View? = focused
        while (v != null) {
            if (v === grid) return true
            v = v.parent as? View
        }
        return false
    }

    private fun isUnderTitleView(focused: View): Boolean {
        val title = titleView ?: return false
        var v: View? = focused
        while (v != null) {
            if (v === title) return true
            v = v.parent as? View
        }
        return false
    }

    private fun maybeLoadMore(rowId: Long, selectedIndex: Int) {
        if (selectedIndex < 0) return
        val state = rowPaging[rowId] ?: return
        if (state.collection.isBlank()) return
        if (state.loadingMore) return
        if (state.total > 0 && state.allItems.size >= state.total) return
        if (selectedIndex < state.loadedCount - PREFETCH_THRESHOLD) return
        state.loadingMore = true
        val repo = (requireActivity().application as WebunimeApp).catalogRepository
        viewLifecycleOwner.lifecycleScope.launch {
            val next = runCatching {
                repo.listCollectionPage(
                    collection = state.collection,
                    page = state.page + 1,
                    genre = state.genre,
                    sort = state.sort,
                )
            }.getOrNull()
            state.loadingMore = false
            if (!isAdded || next == null || next.items.isEmpty()) {
                if (next != null) state.total = state.allItems.size
                return@launch
            }
            state.page = next.page
            state.total = next.total
            for (item in next.items) {
                state.allItems.add(item)
                state.adapter.add(item)
            }
            state.loadedCount = state.allItems.size
            next.items.forEach { CardPresenter.preload(requireContext(), it.thumbnail) }
        }
    }

    private class RowPagingState(
        val allItems: MutableList<CatalogItem>,
        val adapter: ArrayObjectAdapter,
        var loadedCount: Int,
        val collection: String,
        val genre: String = "",
        val sort: String = "",
        var page: Int = 1,
        var total: Int = allItems.size,
        var loadingMore: Boolean = false,
    )

    private data class DeferredRowSpec(
        val title: String,
        val collection: String,
        val genre: String = "",
        val sort: String = "",
    )

    private enum class LibraryRowKind { CONTINUE, FAVORITES }

    companion object {
        private const val PREFETCH_THRESHOLD = 3
        const val TYPE_CONTINUE = "continue"
        const val TYPE_FAVORITE = "favorite"
        private const val HERO_LIMIT = 10

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
