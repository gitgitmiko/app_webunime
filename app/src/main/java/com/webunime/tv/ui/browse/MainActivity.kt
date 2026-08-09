package com.webunime.tv.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.AppUpdateChecker
import com.webunime.tv.data.AppUpdateInfo
import com.webunime.tv.data.CatalogItem
import com.webunime.tv.data.PlayerRouter
import com.webunime.tv.ui.detail.DetailActivity
import com.webunime.tv.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : FragmentActivity() {

    private val updateChecker by lazy { AppUpdateChecker(this) }
    private var updateDialogShown = false
    private var pendingUpdateInfo: AppUpdateInfo? = null
    private var pendingApkFile: File? = null

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
            // Cek OTA paralel dengan load katalog — jangan ditunda sampai sync selesai.
            val updateDeferred = async(Dispatchers.IO) {
                runCatching { updateChecker.fetchAvailableUpdate() }.getOrNull()
            }

            loading.visibility = View.VISIBLE
            val needRemote = repo.needsGithubRefreshToday()
            if (needRemote) {
                loadingText.setText(R.string.updating)
                runCatching { repo.refreshFromGithubOnce() }
            } else {
                loadingText.setText(R.string.loading_catalog)
            }

            if (!repo.isSnapshotReady()) {
                if (needRemote) loadingText.setText(R.string.loading_local_fallback)
                repo.loadStartupShell()
            }

            loading.visibility = View.GONE
            if (!isFinishing) {
                browseFragment()?.reloadRows()
            }

            val info = updateDeferred.await()
            if (!isFinishing && info != null) {
                showUpdateDialog(info)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Setelah user izinkan "Install unknown apps", lanjutkan install.
        val apk = pendingApkFile
        if (apk != null && apk.exists() && updateChecker.canInstallPackages()) {
            pendingApkFile = null
            pendingUpdateInfo = null
            Toast.makeText(this, R.string.update_installing, Toast.LENGTH_SHORT).show()
            runCatching { updateChecker.installApk(this, apk) }
                .onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.update_failed, it.message ?: "install"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }

        val repo = (application as WebunimeApp).catalogRepository
        if (repo.consumeBrowseReloadRequest()) {
            browseFragment()?.reloadRows()
        }
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

    private fun showUpdateDialog(info: AppUpdateInfo) {
        if (updateDialogShown || isFinishing) return
        updateDialogShown = true
        val notes = info.changelog?.takeIf { it.isNotBlank() }.orEmpty()
        // AppCompat + theme Material: AlertDialog bawaan Leanback sering tidak terlihat di TV.
        val themed = ContextThemeWrapper(this, R.style.Theme_WebunimeTv_Detail)
        AlertDialog.Builder(themed)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, info.versionName, notes))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdateDownload(info) }
            .setNegativeButton(R.string.update_later, null)
            .setCancelable(true)
            .show()
    }

    private fun startUpdateDownload(info: AppUpdateInfo) {
        pendingUpdateInfo = info
        lifecycleScope.launch {
            // Ambil ulang version.json sebelum unduh → langsung loncat ke APK terbaru.
            val latest = runCatching { updateChecker.fetchAvailableUpdate() }.getOrNull()
            val toInstall = when {
                latest == null -> info
                latest.versionCode >= info.versionCode -> latest
                else -> info
            }
            pendingUpdateInfo = toInstall

            val progressToast = Toast.makeText(this@MainActivity, "", Toast.LENGTH_SHORT)
            val apk = runCatching {
                updateChecker.downloadApk(toInstall) { pct ->
                    runOnUiThread {
                        progressToast.setText(getString(R.string.update_downloading, pct))
                        progressToast.show()
                    }
                }
            }.getOrElse { err ->
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_failed, err.message ?: "download"),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            if (isFinishing) return@launch
            if (!updateChecker.canInstallPackages()) {
                pendingApkFile = apk
                Toast.makeText(this@MainActivity, R.string.update_need_permission, Toast.LENGTH_LONG).show()
                updateChecker.openInstallPermissionSettings(this@MainActivity)
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, R.string.update_installing, Toast.LENGTH_SHORT).show()
                runCatching { updateChecker.installApk(this@MainActivity, apk) }
                    .onFailure {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_failed, it.message ?: "install"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
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

    fun openDetail(slug: String, episode: Int? = null, season: Int? = null) {
        lifecycleScope.launch {
            val repo = (application as WebunimeApp).catalogRepository
            repo.findBySlugEnsured(slug)
            if (isFinishing) return@launch
            val intent = Intent(this@MainActivity, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_SLUG, slug)
            if (episode != null && episode > 0) {
                intent.putExtra(DetailActivity.EXTRA_EPISODE, episode)
            }
            if (season != null && season > 0) {
                intent.putExtra(DetailActivity.EXTRA_SEASON, season)
            }
            startActivity(intent)
        }
    }

    /** Dari baris Lanjutkan: langsung putar dengan resume + fallback server. */
    fun openContinueWatch(card: CatalogItem) {
        val slug = card.slug?.takeIf { it.isNotBlank() } ?: return
        val episodeNum = card.episode?.takeIf { it > 0 }
        lifecycleScope.launch {
            val app = application as WebunimeApp
            val found = app.catalogRepository.findBySlugEnsured(slug) ?: run {
                Toast.makeText(this@MainActivity, "Judul tidak ditemukan di katalog", Toast.LENGTH_SHORT).show()
                openDetail(slug, episodeNum)
                return@launch
            }
            val episode = episodeNum?.let { ep ->
                found.episodes?.firstOrNull { it.episode == ep }
            }
            val players = PlayerRouter.preferredPlayers(found, episode)
            if (players.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.error_no_players, Toast.LENGTH_SHORT).show()
                openDetail(slug, episodeNum)
                return@launch
            }
            val session = app.watchSessions.get(slug, episodeNum)
            val title = buildString {
                append(found.displayTitle())
                episode?.let { append(" · ").append(it.displayTitle()) }
                    ?: episodeNum?.let { append(" · E$it") }
            }
            startActivity(
                Intent(this@MainActivity, PlayerActivity::class.java)
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
    }

    private fun browseFragment(): BrowseFragment? =
        supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? BrowseFragment
}
