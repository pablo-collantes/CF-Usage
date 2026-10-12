package io.github.pablocollantes.cfusage.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pablocollantes.cfusage.container
import io.github.pablocollantes.cfusage.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * The safety net under the push stream: about every 30 minutes, restart the monitor service if it
 * died and fetch the summary directly, so widgets and missed alerts catch up either way. While the
 * stream is healthy the fetch is skipped: it would only repeat what the stream just sent.
 */
class SafetyNetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.prefs.monitorEnabledNow()) return Result.success()
        if (!MonitorService.isRunning) MonitorService.start(applicationContext)
        val age = System.currentTimeMillis() - c.summaryStore.receivedAt.value
        if (streamHealthy(c.live.state.value.connected, age, c.streamEverySeconds())) return Result.success()
        return runCatching {
            val s = c.api.summary()
            c.summaryStore.update(s)
            WidgetUpdater.updateNow(applicationContext)
            s.alerts.forEach { Notifications.postAlertOnce(applicationContext, it) }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "safety-net"

        /** Connected, and the last summary is no older than the update rate plus two minutes. */
        fun streamHealthy(connected: Boolean, summaryAgeMs: Long, everySeconds: Int): Boolean =
            connected && summaryAgeMs in 0..(everySeconds * 1000L + 2 * 60_000L)

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SafetyNetWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<SafetyNetWorker>().build(),
            )
        }
    }
}
