package com.opendash.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer build of OpenDash, downloads it and installs it.
 *
 * Silent installs: Android 12+ lets an app update itself without a prompt once
 * it's the "installer of record", which happens after its first self-update.
 * Until then (and on Android 10/11), Android shows a one-tap "Update" prompt.
 */
object UpdateManager {

    private const val REPO = "cupidsrage/OpenDash"
    private const val ASSET = "OpenDash.apk"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 77
    const val EXTRA_INTERACTIVE = "interactive"
    const val EXTRA_VERSION = "version"

    sealed class Result {
        object UpToDate : Result()
        data class Installing(val version: String) : Result()
        data class Deferred(val version: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    data class Release(val versionCode: Int, val name: String, val apkUrl: String)

    /** True while the app is running on the car screen; updates wait until you park. */
    @Volatile
    var carConnected = false

    /** Blocking: call from a background thread. */
    fun checkAndInstall(ctx: Context, interactive: Boolean): Result {
        val release = try {
            latestRelease()
        } catch (e: Exception) {
            return Result.Failed("Couldn't reach GitHub (${e.message ?: e.javaClass.simpleName})")
        } ?: return Result.Failed("No releases published yet")

        if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.UpToDate
        if (carConnected || ProjectionService.isActive) return Result.Deferred(release.name)

        return try {
            val apk = download(ctx, release)
            install(ctx, apk, release.name, interactive)
            Result.Installing(release.name)
        } catch (e: Exception) {
            Result.Failed("Update failed (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun latestRelease(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "OpenDash-updater")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            when (conn.responseCode) {
                404 -> return null
                200 -> Unit
                else -> throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")                 // e.g. "v12"
            val code = tag.trimStart('v', 'V').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == ASSET) {
                    return Release(code, "0.1.$code", a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun download(ctx: Context, release: Release): File {
        val out = File(ctx.cacheDir, "update-${release.versionCode}.apk")
        if (out.exists() && out.length() > 0) return out
        ctx.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }

        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "OpenDash-updater")
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                hops++
                continue
            }
            if (code != 200) throw IllegalStateException("download HTTP $code")
            break
        }
        val tmp = File(out.path + ".part")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (!tmp.renameTo(out)) throw IllegalStateException("couldn't save update")
        return out
    }

    private fun install(ctx: Context, apk: File, version: String, interactive: Boolean) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val status = Intent(ctx, UpdateReceiver::class.java)
                .putExtra(EXTRA_INTERACTIVE, interactive)
                .putExtra(EXTRA_VERSION, version)
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, status,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
        }
    }

    fun notify(ctx: Context, title: String, text: String, tap: PendingIntent?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone_screen)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }
}
