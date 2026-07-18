package com.webunime.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.ui.detail.DetailActivity
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, BrowseFragment())
                .commitNow()
        }

        val repo = (application as WebunimeApp).catalogRepository
        lifecycleScope.launch {
            repo.loadInitial()
            browseFragment()?.reloadRows()
            runCatching { repo.refreshFromGithub() }
            browseFragment()?.reloadRows()
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            browseFragment()?.restoreRowFocus()
                ?: run {
                    window.decorView.requestFocus()
                    browseFragment()?.view?.requestFocus()
                }
        }
    }

    fun openDetail(slug: String, episode: Int? = null) {
        val intent = Intent(this, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_SLUG, slug)
        if (episode != null && episode > 0) {
            intent.putExtra(DetailActivity.EXTRA_EPISODE, episode)
        }
        startActivity(intent)
    }

    private fun browseFragment(): BrowseFragment? =
        supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? BrowseFragment
}
