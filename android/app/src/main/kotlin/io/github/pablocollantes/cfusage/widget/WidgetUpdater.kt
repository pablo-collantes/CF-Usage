package io.github.pablocollantes.cfusage.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Widget refreshes, at most one every 30 s. Summaries arrive at the update rate (a minute at the
 * most often), but a reconnect burst shouldn't re-render every widget several times in a row.
 */
object WidgetUpdater {
    private const val MIN_GAP_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastRun = 0L
    private var pending: Job? = null

    @Synchronized
    fun request(context: Context) {
        val app = context.applicationContext
        val wait = lastRun + MIN_GAP_MS - System.currentTimeMillis()
        if (wait <= 0) {
            lastRun = System.currentTimeMillis()
            scope.launch { updateNow(app) }
        } else if (pending?.isActive != true) {
            pending = scope.launch {
                delay(wait)
                synchronized(this@WidgetUpdater) { lastRun = System.currentTimeMillis() }
                updateNow(app)
            }
        }
    }

    /** A design was edited: redraw now, without the throttle, so the home screen follows the editor. */
    fun designsChanged(context: Context) {
        val app = context.applicationContext
        scope.launch { updateNow(app) }
    }

    suspend fun updateNow(context: Context) {
        runCatching {
            OverviewWidget().updateAll(context)
            MetricWidget().updateAll(context)
            TopWorkersWidget().updateAll(context)
        }
    }
}
