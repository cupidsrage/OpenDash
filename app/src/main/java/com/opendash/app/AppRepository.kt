package com.opendash.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

/** Lists and launches the phone's installed apps. */
class AppRepository(private val ctx: Context) {

    private var cache: List<AppEntry>? = null

    fun all(refresh: Boolean = false): List<AppEntry> {
        if (!refresh) cache?.let { return it }
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }

    fun byPackage(pkg: String): AppEntry? = all().firstOrNull { it.packageName == pkg }

    /** Loose name match for voice: exact, then starts-with, then contains. */
    fun findByName(spoken: String): AppEntry? {
        val q = spoken.lowercase().trim().removePrefix("the ").replace(Regex("[^a-z0-9 ]"), "")
        if (q.isEmpty()) return null
        val apps = all()
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "")
        return apps.firstOrNull { norm(it.label) == q }
            ?: apps.firstOrNull { norm(it.label).startsWith(q) }
            ?: apps.firstOrNull { norm(it.label).contains(q) }
            ?: apps.firstOrNull { q.contains(norm(it.label)) && it.label.length > 2 }
    }

    /** Opens an app on the phone (which is mirrored to the car). */
    fun launch(pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            Prefs.addRecent(ctx, pkg)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startIntent(intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
