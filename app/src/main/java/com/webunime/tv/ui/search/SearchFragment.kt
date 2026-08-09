package com.webunime.tv.ui.search

import android.os.Bundle
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.ui.browse.CardPresenter

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val cardPresenter = CardPresenter()
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            val catalog = item as? CatalogItem ?: return@OnItemViewClickedListener
            val slug = catalog.slug?.takeIf { it.isNotBlank() }
                ?: catalog.anime_slug?.takeIf { it.isNotBlank() }
                ?: catalog.series_slug?.takeIf { it.isNotBlank() }
                ?: return@OnItemViewClickedListener
            (activity as? SearchActivity)?.openDetail(slug, catalog.episode, catalog.season)
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
        rowsAdapter.clear()
        if (q.length < 2) return

        val snap = (requireActivity().application as WebunimeApp).catalogRepository.snapshot
        val results = snap.search(q)
        if (results.isEmpty()) return

        val list = ArrayObjectAdapter(cardPresenter)
        results.forEach { list.add(it) }
        val header = HeaderItem(
            0,
            getString(R.string.row_search_results) + " · ${results.size}"
        )
        rowsAdapter.add(ListRow(header, list))
    }
}
