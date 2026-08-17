package com.webunime.tv.ui.search

import android.os.Bundle
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.browse.CardPresenter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()
    private var lastQuery: String = ""
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rowsAdapter = ArrayObjectAdapter(object : ListRowPresenter() {
            override fun initializeRowViewHolder(vh: androidx.leanback.widget.RowPresenter.ViewHolder) {
                super.initializeRowViewHolder(vh)
                val listVh = vh as? ListRowPresenter.ViewHolder ?: return
                CardPresenter.styleCatalogRow(listVh.gridView)
            }
        })
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            val catalog = item as? CatalogItem ?: return@OnItemViewClickedListener
            val slug = catalog.detailSlug().takeIf { it.isNotBlank() } ?: return@OnItemViewClickedListener
            (activity as? SearchActivity)?.openDetail(slug, catalog.episode, catalog.season, catalog.detailCollection())
        })
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        updateResults(newQuery.orEmpty())
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        updateResults(query.orEmpty())
        return true
    }

    private fun updateResults(query: String) {
        val q = query.trim()
        if (q == lastQuery) return
        lastQuery = q
        searchJob?.cancel()
        rowsAdapter.clear()
        if (q.length < 2) return
        searchJob = lifecycleScope.launch {
            delay(280)
            val results = runCatching {
                (requireActivity().application as WebunimeApp).catalogRepository.search(q)
            }.getOrDefault(emptyList())
            if (!isAdded || q != lastQuery) return@launch
            rowsAdapter.clear()
            if (results.isEmpty()) return@launch
            val list = ArrayObjectAdapter(cardPresenter)
            results.forEach { list.add(it) }
            val header = HeaderItem(
                0,
                getString(R.string.row_search_results) + " · ${results.size}"
            )
            rowsAdapter.add(ListRow(header, list))
        }
    }
}
