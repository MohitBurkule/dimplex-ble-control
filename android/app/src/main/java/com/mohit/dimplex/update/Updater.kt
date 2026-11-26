package com.mohit.dimplex.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Self-update from GitHub releases (same approach as MyoLift). CI publishes every main build as
 * release `build-N` with one APK, signed with a fixed key; N is the versionCode. The APK is streamed
 * into a PackageInstaller session. The first update needs a confirm tap; after that the app is the
 * installer of record, so on Android 12+ later updates go through without a prompt.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/MohitBurkule/dimplex-ble-control/releases/latest"

    data class Release(val build: Long, val apkUrl: String)
    data class State(val phase: String = "idle", val progress: Double = 0.0, val message: String? = null)

    val state = MutableStateFlow(State())
    val available = MutableStateFlow<Release?>(null)
    @Volatile private var busy = false

    private fun emit(phase: String, progress: Double = 0.0, message: String? = null) {
        if (phase == "done" || phase == "error") busy = false
        state.value = State(phase, progress, message)
    }

    fun versionCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Returns the newer release, or null when up to date / unreachable. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            if (conn.responseCode !in 200..299) return@withContext null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = j.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return@withContext null
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                ?: return@withContext null
            val r = Release(build, apk)
            (if (build > versionCode(ctx)) r else null).also { available.value = it }
        } catch (e: Exception) {
            null
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, url: String): Boolean {
        if (busy) return false
        busy = true
        val app = ctx.applicationContext
        thread(name = "dimplex-update") {
            var session: PackageInstaller.Session? = null
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                val s = installer.openSession(id)
                session = s
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                if (conn.responseCode !in 200..299) throw Exception("download failed: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    s.openWrite("dimplex.apk", 0, if (total > 0) total else -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - last > total / 50) { last = done; emit("download", done.toDouble() / total) }
                        }
                        s.fsync(out)
                    }
                }
                emit("install", 1.0)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java).setPackage(app.packageName), flags)
                s.commit(pi.intentSender)
                s.close()
            } catch (e: Exception) {
                session?.abandon()
                emit("error", 0.0, e.message ?: e.toString())
            }
        }
        return true
    }
}

/** Result of the install session: ask the user to confirm, or report failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Updater.state.value = Updater.State("confirm", 1.0)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = Updater.State("done", 1.0)
            else -> Updater.state.value = Updater.State("error", 0.0,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed ($status)")
        }
    }
}
