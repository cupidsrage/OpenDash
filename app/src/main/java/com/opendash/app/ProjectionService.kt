package com.opendash.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Holds the screen-share session and renders the phone's screen into
 * whatever surface the car UI hands us.
 */
class ProjectionService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.opendash.app.STOP"
        private const val CHANNEL = "projection"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: ProjectionService? = null
            private set

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addListener(l: () -> Unit) = listeners.add(l)
        fun removeListener(l: () -> Unit) = listeners.remove(l)
        private fun notifyListeners() = listeners.forEach { it() }

        val isActive: Boolean get() = instance?.projection != null

        fun requestStart(c: Context) {
            if (isActive) return
            // The "Start now" prompt has to appear on the phone, not the car.
            c.startActivity(
                Intent(c, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                AppRepository.onPhone()
            )
        }

        fun stop(c: Context) {
            instance?.stopProjection()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var mirror: VirtualDisplay? = null
    private var landscapeView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProjection()
            return START_NOT_STICKY
        }

        // Must be in the foreground before getMediaProjection() on Android 14+.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (code != Activity.RESULT_OK || data == null) {
            stopProjection()
            return START_NOT_STICKY
        }

        releaseMirror()
        projection?.stop()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also { p ->
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { stopProjection() }
                }
            }, handler)
        }
        instance = this
        setLandscapeLock(Prefs.forceLandscape(this))
        notifyListeners()
        return START_NOT_STICKY
    }

    /** Point the phone mirror at [surface] (sized [w] x [h] pixels). */
    fun attachMirror(surface: Surface, w: Int, h: Int) {
        val p = projection ?: return
        if (w <= 0 || h <= 0) return
        val dpi = resources.displayMetrics.densityDpi
        val vd = mirror
        if (vd == null) {
            // Android 14+: only one virtual display per projection, so we reuse it after this.
            mirror = p.createVirtualDisplay(
                "OpenDashMirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = surface
        }
    }

    fun detachMirror() {
        mirror?.surface = null
    }

    fun stopProjection() {
        releaseMirror()
        projection?.let { p ->
            projection = null
            try { p.stop() } catch (_: Exception) {}
        }
        setLandscapeLock(false)
        instance = null
        notifyListeners()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseMirror() {
        mirror?.release()
        mirror = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Phone rotated: the car UI re-fits the mirror.
        notifyListeners()
    }

    override fun onDestroy() {
        if (instance === this) {
            releaseMirror()
            projection?.stop()
            projection = null
            setLandscapeLock(false)
            instance = null
            notifyListeners()
        }
        super.onDestroy()
    }

    /**
     * An invisible overlay that requests landscape, so the phone's screen
     * matches the car's wide display instead of a tall strip.
     */
    private fun setLandscapeLock(on: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        if (on && landscapeView == null && Settings.canDrawOverlays(this)) {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            try {
                wm.addView(v, lp)
                landscapeView = v
            } catch (_: Exception) {}
        } else if (!on) {
            landscapeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            landscapeView = null
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Car screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone_screen)
            .setContentTitle("OpenDash is showing your phone on the car screen")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setColor(ContextCompat.getColor(this, R.color.launcher_bg))
            .build()
    }
}
