package com.webunime.tv.ui.settings

import android.os.Bundle
import android.view.KeyEvent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pengaturan: OTA APK + scrape manual + sync katalog dari GitHub.
 */
class SettingsActivity : AppCompatActivity() {

    private val updateChecker by lazy { AppUpdateChecker(this) }
    private val scrapeClient by lazy { ScrapeTriggerClient() }
    private val catalogRepo by lazy { (application as WebunimeApp).catalogRepository }

    private var pendingApkFile: File? = null
    private var checking = false
    private var catalogBusy = false

    private lateinit var catalogStatusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.settingsVersion).text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        catalogStatusView = findViewById(R.id.settingsCatalogStatus)

        val checkBtn = findViewById<MaterialButton>(R.id.settingsCheckUpdate)
        checkBtn.setOnClickListener { checkForUpdate() }
        checkBtn.requestFocus()

        findViewById<MaterialButton>(R.id.settingsStartScrape)
            .setOnClickListener { startScrape() }
        findViewById<MaterialButton>(R.id.settingsRefreshCatalog)
            .setOnClickListener { refreshCatalog() }

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

    private fun refreshCatalogStatusLabel() {
        lifecycleScope.launch {
            val status = runCatching { catalogRepo.fetchSyncStatus() }.getOrNull()
            if (isFinishing) return@launch
            catalogStatusView.text = when {
                status == null -> getString(R.string.settings_catalog_hint)
                status.isRunning() -> getString(R.string.settings_catalog_processing)
                status.isFailed() ->
                    status.message?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.settings_catalog_hint)
                else -> getString(R.string.settings_catalog_hint)
            }
        }
    }

    private fun startScrape() {
        if (catalogBusy || isFinishing) return
        catalogBusy = true
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
                }
                result.errorCode == "already_running" || result.httpCode == 409 -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_scrape_running,
                        Toast.LENGTH_LONG,
                    ).show()
                    catalogStatusView.setText(R.string.settings_catalog_processing)
                }
                result.errorCode == "rate_limited" || result.httpCode == 429 -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_scrape_rate_limited,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {
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
            val ok = runCatching { catalogRepo.forceRefreshFromGithub() }.getOrDefault(0)
            catalogBusy = false
            if (isFinishing) return@launch
            if (ok > 0) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_catalog_updated, ok),
                    Toast.LENGTH_LONG,
                ).show()
                catalogStatusView.text = getString(R.string.settings_catalog_updated, ok)
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
}
