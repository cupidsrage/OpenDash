package com.opendash.app

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("opendash", Context.MODE_PRIVATE)

    fun forceLandscape(c: Context) = sp(c).getBoolean("landscape", true)
    fun setForceLandscape(c: Context, v: Boolean) = sp(c).edit().putBoolean("landscape", v).apply()

    fun blockVideo(c: Context) = sp(c).getBoolean("block_video", true)
    fun setBlockVideo(c: Context, v: Boolean) = sp(c).edit().putBoolean("block_video", v).apply()

    fun recents(c: Context): List<String> =
        sp(c).getString("recents", "")!!.split(',').filter { it.isNotBlank() }

    fun addRecent(c: Context, pkg: String) {
        val list = (listOf(pkg) + recents(c).filter { it != pkg }).take(8)
        sp(c).edit().putString("recents", list.joinToString(",")).apply()
    }

    /** Apps whose video gets covered while the car is moving (if the setting is on). */
    val VIDEO_APPS = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.wbd.stream",
        "com.peacocktv.peacockandroid",
        "com.cbs.app",
        "tv.twitch.android.app",
        "com.zhiliaoapp.musically",
        "com.plexapp.android",
        "org.videolan.vlc",
    )

    /** Recent apps shown on Home before you've launched anything. */
    val DEFAULT_FAVORITES = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.pandora.android",
        "com.google.android.youtube",
    )
}

/** Real size of the phone's own screen in its current rotation. */
fun phoneScreenSize(c: Context): Point {
    val dm = c.getSystemService(DisplayManager::class.java)
    val p = Point()
    @Suppress("DEPRECATION")
    dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
    return p
}
