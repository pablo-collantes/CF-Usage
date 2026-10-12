package io.github.pablocollantes.cfusage.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.pablocollantes.cfusage.MainActivity
import io.github.pablocollantes.cfusage.R
import io.github.pablocollantes.cfusage.data.Alert
import io.github.pablocollantes.cfusage.data.KillTarget
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.data.statusRank
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.Times

object Notifications {
    const val CH_MONITOR = "monitor"
    const val CH_ALERTS = "alerts"
    const val CH_INFO = "info"
    const val CH_HEALTH = "health"

    const val SERVICE_ID = 1
    const val UNREACHABLE_ID = 2

    private const val SEEN_PREFS = "notified_alerts"
    private const val SEEN_KEY = "ids"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MONITOR, "Monitor connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "The permanent notification that keeps the live connection to the usage server open."
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, "Usage alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Usage is approaching or past the included allowance."
                },
                NotificationChannel(CH_INFO, "Usage notices", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Early notices, such as 50% of an allowance used."
                },
                NotificationChannel(CH_HEALTH, "Monitor unreachable", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The usage server could not be reached for more than 10 minutes."
                },
            ),
        )
    }

    private fun openApp(context: Context, requestCode: Int, route: String?, kill: KillTarget? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
            kill?.let { MainActivity.putKill(this, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** "Workers requests 12% · updated 21:39": the highest metric and the server's last poll. */
    fun serviceText(summary: Summary?, connected: Boolean): String {
        if (summary == null) return if (connected) "Connected, waiting for data" else "Connecting to the usage server…"
        val top = summary.metrics.maxWithOrNull(compareBy({ statusRank(it.status) }, { it.pct }))
        val updated = Times.parse(summary.lastPollAt)?.let { "updated ${Times.hhmm(it)}" } ?: "no poll yet"
        val head = top?.let { "${it.label} ${Fmt.pct(it.pct)}" } ?: "No metrics"
        return if (connected) "$head · $updated" else "$head · $updated · reconnecting"
    }

    fun serviceNotification(context: Context, summary: Summary?, connected: Boolean) =
        NotificationCompat.Builder(context, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_cf)
            .setContentTitle("CF Usage")
            .setContentText(serviceText(summary, connected))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(context, 1, null))
            .build()

    @SuppressLint("MissingPermission") // canPost() is the check
    fun updateService(context: Context, summary: Summary?, connected: Boolean) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(SERVICE_ID, serviceNotification(context, summary, connected))
    }

    private fun canPost(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts [alert] unless it was already notified (alerts arrive both as events and in summaries). */
    @Synchronized
    fun postAlertOnce(context: Context, alert: Alert) {
        if (alert.ackedAt != null) return
        val prefs = context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(SEEN_KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        if (alert.id in seen) return
        if (!canPost(context)) return
        postAlert(context, alert)
        // Bounded history: alert ids are per cycle, so a few hundred covers many months.
        prefs.edit().putString(SEEN_KEY, (seen + alert.id).takeLast(300).joinToString("\n")).apply()
    }

    @SuppressLint("MissingPermission") // only called after canPost()
    private fun postAlert(context: Context, alert: Alert) {
        val id = 1000 + (alert.id.hashCode() and 0x7fffffff) % 100_000
        val channel = if (alert.severity == "info") CH_INFO else CH_ALERTS
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_cf)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setCategory(NotificationCompat.CATEGORY_ALARM.takeIf { alert.severity == "critical" } ?: NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (channel == CH_ALERTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setColor(if (alert.severity == "critical") 0xFFDC2626.toInt() else 0xFFF59E0B.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id, MainActivity.ROUTE_ALERTS))
            .addAction(0, "Open", openApp(context, id + 1, MainActivity.ROUTE_ALERTS))
        // Never a one-tap kill: the action only opens the confirmation sheet, which asks for unlock.
        alert.suggested.firstOrNull()?.let { target ->
            b.addAction(0, "Review kill switch", openApp(context, id + 2, MainActivity.ROUTE_KILL, target))
        }
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    @SuppressLint("MissingPermission") // canPost() is the check
    fun postUnreachable(context: Context, minutes: Long, error: String?) {
        if (!canPost(context)) return
        val text = "No connection to the usage server for $minutes min. Alerts can't reach this phone." +
            (error?.let { "\n$it" } ?: "")
        val n = NotificationCompat.Builder(context, CH_HEALTH)
            .setSmallIcon(R.drawable.ic_stat_cf)
            .setContentTitle("Usage monitor unreachable")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, UNREACHABLE_ID, null))
            .build()
        NotificationManagerCompat.from(context).notify(UNREACHABLE_ID, n)
    }

    fun clearUnreachable(context: Context) = NotificationManagerCompat.from(context).cancel(UNREACHABLE_ID)
}
