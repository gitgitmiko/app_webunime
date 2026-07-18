package com.webunime.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
        // Fokus ke grid kartu — jangan ke decorView/root (merusak D-pad di TCL)
        window.decorView.post {
            browseFragment()?.restoreRowFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            browseFragment()?.restoreRowFocus()
        }
    }

    /**
     * Beberapa remote TCL mengirim ENTER/BUTTON_A untuk OK.
     * Jika fokus hilang, panah pertama mengembalikan fokus ke grid.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isDpadOrOk(code) && event.action == KeyEvent.ACTION_DOWN) {
            val browse = browseFragment()
            val grid = browse?.rowsGrid()
            if (grid != null && !grid.hasFocus()) {
                browse.restoreRowFocus()
            }
        }

        val normalized = normalizeOkKey(event) ?: event
        return super.dispatchKeyEvent(normalized)
    }

    private fun isDpadOrOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

    private fun normalizeOkKey(event: KeyEvent): KeyEvent? {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return null
        }
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            mapped,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source
        )
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
