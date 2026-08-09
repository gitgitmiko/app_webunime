package com.webunime.tv.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.ui.detail.DetailActivity
import kotlinx.coroutines.launch

class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        lifecycleScope.launch {
            // Search butuh seluruh katalog — load on-demand di sini saja.
            runCatching {
                (application as WebunimeApp).catalogRepository.ensureAllSections()
            }
            if (savedInstanceState == null && !isFinishing) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.search_fragment, SearchFragment())
                    .commitNow()
            }
        }
    }

    fun openDetail(slug: String, episode: Int? = null, season: Int? = null) {
        val intent = Intent(this, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_SLUG, slug)
        if (episode != null && episode > 0) {
            intent.putExtra(DetailActivity.EXTRA_EPISODE, episode)
        }
        if (season != null && season > 0) {
            intent.putExtra(DetailActivity.EXTRA_SEASON, season)
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
