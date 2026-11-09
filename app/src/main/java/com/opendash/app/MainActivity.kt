package com.opendash.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/** One-time setup checklist on the phone. */
class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    private val runtimePerms: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!autoChecked) {
            autoChecked = true
            checkForUpdates(interactive = true)
        }
    }

    private var updateStatus: String? = null
    private var autoChecked = false

    private fun checkForUpdates(interactive: Boolean) {
        if (!packageManager.canRequestPackageInstalls()) {
            updateStatus = "Turn on step 5 to get updates."
            render()
            return
        }
        updateStatus = "Checking for updates…"
        render()
        Thread {
            val result = UpdateManager.checkAndInstall(applicationContext, interactive)
            runOnUiThread {
                updateStatus = when (result) {
                    UpdateManager.Result.UpToDate -> "You're on the latest version."
                    is UpdateManager.Result.Installing -> "Installing ${result.version}…"
                    is UpdateManager.Result.Deferred -> "${result.version} will install when you leave the car."
                    is UpdateManager.Result.Failed -> result.reason
                }
                if (!isFinishing) render()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        list.removeAllViews()

        text("OpenDash", 30f, bold = true)
        text("Runs any app on your car screen through Android Auto. Do these once, then plug in (or connect wirelessly) and open OpenDash in Android Auto.", 15f)

        step(
            "1. Display over other apps",
            "Lets OpenDash open apps from the car screen and keep the phone in landscape.",
            Settings.canDrawOverlays(this)
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        step(
            "2. Touch control (Accessibility)",
            "Settings → Accessibility → OpenDash → On. This is what makes taps on the car screen reach your phone.",
            TouchInjectorService.isEnabled(this)
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        step(
            "3. Microphone, location & notifications",
            "Microphone for voice commands, location to pause video while driving, notifications for the screen-share indicator.",
            runtimePerms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        ) {
            requestPermissions(runtimePerms, 1)
        }

        step(
            "4. Allow OpenDash in Android Auto",
            "Open Android Auto settings (Settings → Connected devices → Android Auto). Scroll down and tap Version about 10 times until developer mode turns on. Tap ⋮ → Developer settings → turn on Unknown sources. Back in Android Auto settings → Customize launcher, make sure OpenDash is checked.",
            null,
            buttonLabel = "Open Android Auto"
        ) {
            openAndroidAutoSettings()
        }

        step(
            "5. Allow automatic updates",
            "Lets OpenDash install new versions of itself from GitHub. Turn on “Allow from this source”.",
            packageManager.canRequestPackageInstalls()
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }

        step(
            "6. Keep the phone unlocked in the car",
            "A locked phone shows its lock screen on the car display. Turn on Smart Lock / Extend Unlock with your car's Bluetooth as a trusted device.",
            null,
            buttonLabel = "Open security settings"
        ) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }

        header("Options")
        toggle("Keep phone in landscape while connected", Prefs.forceLandscape(this)) {
            Prefs.setForceLandscape(this, it)
        }
        toggle("Pause video apps while the car is moving", Prefs.blockVideo(this)) {
            Prefs.setBlockVideo(this, it)
        }

        header("Test")
        if (ProjectionService.isActive) {
            button("Stop screen sharing") { ProjectionService.stop(this); render() }
        } else {
            button("Try screen sharing now") { ProjectionService.requestStart(this) }
        }
        header("Updates")
        text("Version ${BuildConfig.VERSION_NAME}. Checks GitHub every few hours and installs new versions automatically (never while you're connected to the car).", 14f)
        updateStatus?.let { text(it, 14f) }
        button("Check for updates now") { checkForUpdates(interactive = true) }

        text("On the car: Home shows recent apps, the grid button shows every app, the phone button shows your phone's screen. The blue mic takes “open Spotify”, “navigate to Walmart”, or “play some Zach Bryan”.", 13f)
    }

    private fun openAndroidAutoSettings() {
        val gearhead = "com.google.android.projection.gearhead"
        val direct = packageManager.getLaunchIntentForPackage(gearhead)
        try {
            startActivity(
                direct ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$gearhead"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- tiny view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false) {
        list.addView(TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    private fun header(s: String) {
        list.addView(TextView(this).apply {
            text = s
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(4))
        })
    }

    private fun step(title: String, desc: String, done: Boolean?, buttonLabel: String = "Open", action: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }
        box.addView(TextView(this).apply {
            text = when (done) {
                true -> "✅  $title"
                false -> "⬜  $title"
                null -> "ℹ️  $title"
            }
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = desc
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        })
        if (done != true) {
            box.addView(Button(this).apply {
                text = buttonLabel
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START })
        }
        list.addView(box)
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        list.addView(SwitchCompat(this).apply {
            text = label
            textSize = 15f
            isChecked = value
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
    }

    private fun button(label: String, action: () -> Unit) {
        list.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }
}
