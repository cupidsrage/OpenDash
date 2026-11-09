package com.opendash.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Receives the result of an update install. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val version = intent.getStringExtra(UpdateManager.EXTRA_VERSION) ?: ""
        val interactive = intent.getBooleanExtra(UpdateManager.EXTRA_INTERACTIVE, false)

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a one-tap confirmation (first update, or Android 10/11).
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (interactive) {
                    try { ctx.startActivity(confirm); return } catch (_: Exception) {}
                }
                val tap = PendingIntent.getActivity(
                    ctx, 0, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                UpdateManager.notify(ctx, "OpenDash update ready", "Tap to install $version", tap)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateManager.notify(ctx, "OpenDash updated", "Now on $version", null)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                UpdateManager.notify(ctx, "OpenDash update failed", msg, null)
            }
        }
    }
}
