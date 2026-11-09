package com.opendash.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Invisible activity that shows Android's "Start screen sharing?" prompt. */
class ProjectionRequestActivity : Activity() {

    private val req = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Skip the "single app" option: the car needs the whole screen.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == req && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ProjectionService::class.java)
                .putExtra(ProjectionService.EXTRA_CODE, resultCode)
                .putExtra(ProjectionService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, svc)
        }
        finish()
    }
}
