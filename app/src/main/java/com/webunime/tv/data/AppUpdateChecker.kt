package com.webunime.tv.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.webunime.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = false)
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String? = null,
)

/**
 * Self-update tanpa Play Store:
 * 1) Baca update/version.json (GitHub raw + fallback jsDelivr)
 * 2) Jika versionCode lebih tinggi → unduh APK
 * 3) Install lewat Package Installer
 */
class AppUpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(AppUpdateInfo::class.java)

    suspend fun fetchAvailableUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val bust = System.currentTimeMillis()
        val urls = listOf(
            "$VERSION_JSON_URL?t=$bust",
            "$VERSION_JSON_JSDELIVR?t=$bust",
        )
        var info: AppUpdateInfo? = null
        for (url in urls) {
            info = fetchVersionJson(url)
            if (info != null) break
        }
        val resolved = info ?: return@withContext null
        if (resolved.versionCode <= BuildConfig.VERSION_CODE) {
            Log.i(TAG, "Up to date: installed=${BuildConfig.VERSION_CODE} remote=${resolved.versionCode}")
            return@withContext null
        }
        if (resolved.apkUrl.isBlank() || !resolved.apkUrl.startsWith("http")) return@withContext null
        Log.i(TAG, "Update available: ${resolved.versionName} (${resolved.versionCode})")
        resolved
    }

    private fun fetchVersionJson(url: String): AppUpdateInfo? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "WEBUNIME-TV/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .get()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "version.json HTTP ${response.code} for $url")
                    return null
                }
                response.body?.string().orEmpty()
            }
            if (body.isBlank()) return null
            parseUpdateInfo(body)
        }.onFailure {
            Log.w(TAG, "version.json fetch failed: $url — ${it.message}")
        }.getOrNull()
    }

    private fun parseUpdateInfo(body: String): AppUpdateInfo? {
        adapter.fromJson(body)?.let { return it }
        // Fallback JSONObject bila Moshi gagal (BOM / whitespace aneh)
        return runCatching {
            val o = JSONObject(body.trim().removePrefix("\uFEFF"))
            AppUpdateInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = o.getString("apkUrl"),
                changelog = o.optString("changelog").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    suspend fun downloadApk(
        info: AppUpdateInfo,
        onProgress: ((percent: Int) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val out = File(dir, "WEBUNIME-TV-update.apk")
        if (out.exists()) out.delete()

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "WEBUNIME-TV/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } >= 0) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                onProgress?.invoke(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (out.length() < 100_000L) error("APK terlalu kecil")
        onProgress?.invoke(100)
        out
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    fun installApk(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    companion object {
        private const val TAG = "AppUpdate"

        const val VERSION_JSON_URL =
            "https://raw.githubusercontent.com/gitgitmiko/app_webunime/main/update/version.json"

        /** CDN mirror — sering lebih cepat / kurang cache ketat daripada raw.githubusercontent. */
        const val VERSION_JSON_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/gitgitmiko/app_webunime@main/update/version.json"
    }
}
