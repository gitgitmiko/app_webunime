package com.webunime.tv.ui.settings

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.webunime.tv.BuildConfig
import com.webunime.tv.R
import com.webunime.tv.WebunimeApp
import com.webunime.tv.data.AppUpdateChecker
import com.webunime.tv.data.AppUpdateInfo
import com.webunime.tv.data.ScrapeTriggerClient
import com.webunime.tv.ui.auth.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pengaturan: akun, OTA APK, scrape manual, dan refresh katalog dari API.
 */
class SettingsActivity : AppCompatActivity() {

    private val updateChecker by lazy { AppUpdateChecker(this) }
    private val scrapeClient by lazy { ScrapeTriggerClient() }
    private val catalogRepo by lazy { (application as WebunimeApp).catalogRepository }
    private val scrapePrefs by lazy {
        getSharedPreferences(PREFS_SCRAPE, Context.MODE_PRIVATE)
    }

    private var pendingApkFile: File? = null
    private var checking = false
    private var catalogBusy = false

    private lateinit var catalogStatusView: TextView
    private lateinit var startScrapeBtn: MaterialButton
    private lateinit var refreshCatalogBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.settingsVersion).text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        catalogStatusView = findViewById(R.id.settingsCatalogStatus)
        startScrapeBtn = findViewById(R.id.settingsStartScrape)
        refreshCatalogBtn = findViewById(R.id.settingsRefreshCatalog)

        val auth = (application as WebunimeApp).authRepository
        val user = auth.currentUser()
        findViewById<TextView>(R.id.settingsAccountName).text = getString(
            R.string.settings_logged_in_as,
            user?.displayLabel() ?: "-",
        )
        val displayNameInput = findViewById<EditText>(R.id.settingsDisplayName)
        displayNameInput.setText(user?.displayName.orEmpty())
        findViewById<MaterialButton>(R.id.settingsSaveProfile).setOnClickListener {
            saveProfile(displayNameInput.text?.toString().orEmpty())
        }
        findViewById<MaterialButton>(R.id.settingsLogout).setOnClickListener { logout() }

        val checkBtn = findViewById<MaterialButton>(R.id.settingsCheckUpdate)
        checkBtn.setOnClickListener { checkForUpdate() }
        checkBtn.requestFocus()

        startScrapeBtn.setOnClickListener { startScrape() }
        refreshCatalogBtn.setOnClickListener { refreshCatalog() }

        applyScrapeButtonLocked(isScrapeLocked())
        refreshCatalogStatusLabel()
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingApkFile
        if (apk != null && apk.exists() && updateChecker.canInstallPackages()) {
            pendingApkFile = null
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
        applyScrapeButtonLocked(isScrapeLocked())
        refreshCatalogStatusLabel()
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

    private fun isScrapeLocked(): Boolean =
        scrapePrefs.getBoolean(KEY_SCRAPE_LOCKED, false)

    private fun setScrapeLocked(locked: Boolean) {
        scrapePrefs.edit().putBoolean(KEY_SCRAPE_LOCKED, locked).apply()
        applyScrapeButtonLocked(locked)
    }

    private fun applyScrapeButtonLocked(locked: Boolean) {
        if (!::startScrapeBtn.isInitialized) return
        startScrapeBtn.isEnabled = !locked
        startScrapeBtn.isFocusable = !locked
        startScrapeBtn.isClickable = !locked
        startScrapeBtn.alpha = if (locked) 0.45f else 1f
    }

    private fun refreshCatalogStatusLabel() {
        lifecycleScope.launch {
            val status = runCatching { catalogRepo.fetchSyncStatus() }.getOrNull()
            if (isFinishing) return@launch
            if (status?.isRunning() == true) {
                setScrapeLocked(true)
                catalogStatusView.setText(R.string.settings_catalog_processing)
                return@launch
            }
            catalogStatusView.text = when {
                status == null -> getString(R.string.settings_catalog_hint)
                status.isFailed() ->
                    status.message?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.settings_catalog_hint)
                else -> getString(R.string.settings_catalog_hint)
            }
            // Status selesai tidak otomatis unlock — tunggu Update data sukses.
            applyScrapeButtonLocked(isScrapeLocked())
        }
    }

    private fun startScrape() {
        if (catalogBusy || isFinishing || isScrapeLocked()) return
        catalogBusy = true
        // Disable segera agar tidak spam sebelum respons proxy.
        setScrapeLocked(true)
        Toast.makeText(this, R.string.settings_scrape_starting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = scrapeClient.startScrape()
            catalogBusy = false
            if (isFinishing) return@launch
            when {
                result.ok -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_scrape_started,
                        Toast.LENGTH_LONG,
                    ).show()
                    catalogStatusView.setText(R.string.settings_catalog_processing)
                    refreshCatalogBtn.requestFocus()
                }
                result.errorCode == "already_running" || result.httpCode == 409 -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_scrape_running,
                        Toast.LENGTH_LONG,
                    ).show()
                    catalogStatusView.setText(R.string.settings_catalog_processing)
                    refreshCatalogBtn.requestFocus()
                }
                result.errorCode == "rate_limited" || result.httpCode == 429 -> {
                    // Gagal mulai → unlock supaya bisa coba lagi nanti.
                    setScrapeLocked(false)
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_scrape_rate_limited,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {
                    setScrapeLocked(false)
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_scrape_failed, result.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun refreshCatalog() {
        if (catalogBusy || isFinishing) return
        catalogBusy = true
        Toast.makeText(this, R.string.settings_catalog_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val status = runCatching { catalogRepo.fetchSyncStatus() }.getOrNull()
            if (isFinishing) {
                catalogBusy = false
                return@launch
            }
            if (status?.isRunning() == true) {
                catalogBusy = false
                setScrapeLocked(true)
                catalogStatusView.setText(R.string.settings_catalog_processing)
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_catalog_processing,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            if (status == null) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_catalog_status_unknown,
                    Toast.LENGTH_SHORT,
                ).show()
            }

            Toast.makeText(this@SettingsActivity, R.string.settings_catalog_updating, Toast.LENGTH_SHORT).show()
            val ok = runCatching { catalogRepo.forceRefreshFromApi() }.getOrDefault(0)
            catalogBusy = false
            if (isFinishing) return@launch
            if (ok > 0) {
                setScrapeLocked(false)
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_catalog_updated,
                    Toast.LENGTH_LONG,
                ).show()
                catalogStatusView.setText(R.string.settings_catalog_updated)
                startScrapeBtn.requestFocus()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_catalog_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun checkForUpdate() {
        if (checking || isFinishing) return
        checking = true
        Toast.makeText(this, R.string.settings_checking_update, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = runCatching { updateChecker.fetchAvailableUpdate() }.getOrNull()
            checking = false
            if (isFinishing) return@launch
            if (info == null) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_up_to_date, BuildConfig.VERSION_NAME),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: AppUpdateInfo) {
        val notes = info.changelog?.takeIf { it.isNotBlank() }.orEmpty()
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
        lifecycleScope.launch {
            val latest = runCatching { updateChecker.fetchAvailableUpdate() }.getOrNull()
            val toInstall = when {
                latest == null -> info
                latest.versionCode >= info.versionCode -> latest
                else -> info
            }

            val progressToast = Toast.makeText(this@SettingsActivity, "", Toast.LENGTH_SHORT)
            val apk = runCatching {
                updateChecker.downloadApk(toInstall) { pct ->
                    runOnUiThread {
                        progressToast.setText(getString(R.string.update_downloading, pct))
                        progressToast.show()
                    }
                }
            }.getOrElse { err ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.update_failed, err.message ?: "download"),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            if (isFinishing) return@launch
            if (!updateChecker.canInstallPackages()) {
                pendingApkFile = apk
                Toast.makeText(this@SettingsActivity, R.string.update_need_permission, Toast.LENGTH_LONG).show()
                updateChecker.openInstallPermissionSettings(this@SettingsActivity)
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, R.string.update_installing, Toast.LENGTH_SHORT).show()
                runCatching { updateChecker.installApk(this@SettingsActivity, apk) }
                    .onFailure {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.update_failed, it.message ?: "install"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    private fun saveProfile(name: String) {
        if (name.isBlank()) {
            Toast.makeText(this, R.string.settings_display_name, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            runCatching { (application as WebunimeApp).authRepository.updateProfile(name) }
                .onSuccess {
                    findViewById<TextView>(R.id.settingsAccountName).text =
                        getString(R.string.settings_logged_in_as, it.displayLabel())
                    Toast.makeText(this@SettingsActivity, R.string.settings_profile_saved, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@SettingsActivity, it.message ?: "Gagal menyimpan", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            runCatching { (application as WebunimeApp).authRepository.logout() }
            (application as WebunimeApp).libraryRepository.clear()
            if (isFinishing) return@launch
            startActivity(
                Intent(this@SettingsActivity, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
        }
    }

    companion object {
        private const val PREFS_SCRAPE = "settings_scrape"
        private const val KEY_SCRAPE_LOCKED = "scrape_button_locked"
    }
}
