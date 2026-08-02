package com.webunime.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.PlayerRouter
import com.webunime.tv.ui.detail.DetailActivity
import com.webunime.tv.ui.player.PlayerActivity
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
        val loading = findViewById<View>(R.id.catalogLoading)
        val loadingText = findViewById<TextView>(R.id.catalogLoadingText)

        lifecycleScope.launch {
            // Langsung GitHub → tampilkan. Tidak load lokal di depan (hindari 2× proses).
            loading.visibility = View.VISIBLE
            loadingText.setText(R.string.updating)

            runCatching { repo.refreshFromGithubOnce() }
            if (!repo.isSnapshotReady()) {
                loadingText.setText(R.string.loading_local_fallback)
                repo.loadInitial()
            }

            loading.visibility = View.GONE
            if (!isFinishing) {
                browseFragment()?.reloadRows()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Jangan paksa fokus di sini — bentrok dengan D-pad (atas/bawah “kepencet sendiri”).
        // BrowseFragment sudah restore posisi sekali setelah kembali dari Detail.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // No-op: merebut fokus di sini memicu bounce atas/bawah di remote TV.
    }

    /**
     * Beberapa remote TCL mengirim ENTER/BUTTON_A untuk OK.
     * Jika fokus hilang, panah pertama mengembalikan fokus ke grid.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isDpadOrOk(code) && event.action == KeyEvent.ACTION_DOWN) {
            val browse = browseFragment()
            // User sedang navigasi → batalkan restore tertunda agar tidak “tarik balik”.
            browse?.cancelPendingRestores()
            val focused = window.decorView.findFocus()
            if (browse != null && focused == null) {
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

    /** Dari baris Lanjutkan: langsung putar dengan resume + fallback server. */
    fun openContinueWatch(card: CatalogItem) {
        val app = application as WebunimeApp
        val slug = card.slug?.takeIf { it.isNotBlank() } ?: return
        val episodeNum = card.episode?.takeIf { it > 0 }
        val found = app.catalogRepository.snapshot.findBySlug(slug) ?: run {
            Toast.makeText(this, "Judul tidak ditemukan di katalog", Toast.LENGTH_SHORT).show()
            openDetail(slug, episodeNum)
            return
        }
        val episode = episodeNum?.let { ep ->
            found.episodes?.firstOrNull { it.episode == ep }
        }
        val players = PlayerRouter.preferredPlayers(found, episode)
        if (players.isEmpty()) {
            Toast.makeText(this, R.string.error_no_players, Toast.LENGTH_SHORT).show()
            openDetail(slug, episodeNum)
            return
        }
        val session = app.watchSessions.get(slug, episodeNum)
        val title = buildString {
            append(found.displayTitle())
            episode?.let { append(" · ").append(it.displayTitle()) }
                ?: episodeNum?.let { append(" · E$it") }
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, players.first().url)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_SERVER, players.first().displayName())
                .putExtra(PlayerActivity.EXTRA_SERVER_URLS, players.mapNotNull { it.url }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_SERVER_LABELS, players.map { it.displayName() }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_SLUG, slug)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episodeNum ?: -1)
                .putExtra(PlayerActivity.EXTRA_THUMBNAIL, found.thumbnail ?: card.thumbnail)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, session?.positionMs ?: 0L)
        )
    }

    private fun browseFragment(): BrowseFragment? =
        supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? BrowseFragment
}
