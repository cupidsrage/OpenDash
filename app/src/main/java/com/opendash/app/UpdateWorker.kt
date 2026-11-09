package com.opendash.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background update check. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        UpdateManager.checkAndInstall(applicationContext, interactive = false)
        return Result.success()
    }

    companion object {
        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Check every 3 hours in the background. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("opendash-update", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Check soon (e.g. right after the car disconnects). */
        fun checkSoon(ctx: Context, delayMinutes: Long = 0) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(network)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("opendash-update-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
