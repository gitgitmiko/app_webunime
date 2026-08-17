package com.webunime.tv.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import com.webunime.tv.R
import com.webunime.tv.ui.detail.DetailActivity

class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment, SearchFragment())
                .commitNow()
        }
    }

    fun openDetail(
        slug: String,
        episode: Int? = null,
        season: Int? = null,
        collection: String? = null,
    ) {
        val intent = Intent(this, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_SLUG, slug)
        if (episode != null && episode > 0) {
            intent.putExtra(DetailActivity.EXTRA_EPISODE, episode)
        }
        if (season != null && season > 0) {
            intent.putExtra(DetailActivity.EXTRA_SEASON, season)
        }
        if (!collection.isNullOrBlank()) {
            intent.putExtra(DetailActivity.EXTRA_COLLECTION, collection)
        }
        startActivity(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
