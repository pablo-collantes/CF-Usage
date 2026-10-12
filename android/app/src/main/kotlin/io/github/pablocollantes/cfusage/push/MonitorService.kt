package io.github.pablocollantes.cfusage.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.pablocollantes.cfusage.container
import io.github.pablocollantes.cfusage.data.Alert
import io.github.pablocollantes.cfusage.data.ApiJson
import io.github.pablocollantes.cfusage.data.Kill
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.util.Backoff
import io.github.pablocollantes.cfusage.util.FreshnessRules
import io.github.pablocollantes.cfusage.widget.WidgetUpdater
import io.github.pablocollantes.cfusage.data.streamEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Foreground service holding one Server-Sent Events stream to the usage server
 * (`/api/v1/events`). This is the app's push channel: no FCM, no ntfy, no Play services.
 * The pattern is the one Sterna (cloudflare-email-client) uses.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backoff = Backoff()
    private var source: EventSource? = null
    private var reconnectJob: Job? = null
    private var watchdog: Job? = null
    private var unreachablePosted = false

    /** Bumped on every (re)connect so callbacks from a replaced stream are ignored. */
    @Volatile
    private var generation = 0

    /** The `every` the open stream asked for, in seconds; -1 before the first connect. */
    @Volatile
    private var openEvery = -1

    private val sseClient by lazy {
        container.api.http.newBuilder()
            // The server pings after 60 s without an event, so 90 s of silence means the path is dead.
            .readTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A network came back: retry now instead of waiting out the backoff.
            if (!container.live.state.value.connected) reconnectNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Notifications.ensureChannels(this)
        // specialUse, not dataSync: Android 15+ budgets dataSync services and then stops them,
        // which silently ended push in Sterna. Play's review of specialUse doesn't apply to adb installs.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.SERVICE_ID,
                Notifications.serviceNotification(this, container.summaryStore.summary.value, false),
                type,
            )
        } catch (e: RuntimeException) {
            // Android 12+ can refuse a background start; the WorkManager check keeps data flowing.
            Log.w(TAG, "startForeground refused; the periodic check carries updates", e)
            stopSelf()
            return
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback) }
        watchdog = scope.launch { watchUnreachable() }
        scope.launch { followUpdateRate() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconnectNow()
        return START_STICKY
    }

    /** Android 15+ FGS timeout. specialUse should never get one; stop cleanly if it does. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type $fgsType); stopping")
        stopSelf()
    }

    @Synchronized
    private fun reconnectNow() {
        reconnectJob?.cancel()
        backoff.reset()
        connect()
    }

    @Synchronized
    private fun connect() {
        val gen = ++generation
        source?.cancel()
        source = null
        scope.launch {
            if (!container.prefs.monitorEnabledNow()) {
                stopSelf()
                return@launch
            }
            val every = container.streamEverySeconds()
            val url = runCatching { container.api.url("events").newBuilder().addQueryParameter("every", every.toString()).build() }.getOrElse {
                onDown(gen, it.message ?: "Bad server URL")
                return@launch
            }
            openEvery = every
            Log.i(TAG, "Connecting, summaries every ${every}s")
            val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
            if (gen != generation) return@launch
            source = EventSources.createFactory(sseClient).newEventSource(request, Listener(gen))
        }
    }

    private inner class Listener(private val gen: Int) : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            if (gen != generation) return
            backoff.reset()
            container.live.up()
            if (unreachablePosted) {
                Notifications.clearUnreachable(this@MonitorService)
                unreachablePosted = false
            }
            Notifications.updateService(this@MonitorService, container.summaryStore.summary.value, true)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (gen != generation) return
            runCatching { handle(type ?: "message", data) }.onFailure { Log.w(TAG, "Bad '$type' event", it) }
        }

        override fun onClosed(eventSource: EventSource) {
            // The server ends every stream after 2 hours; that is a normal reconnect.
            onDown(gen, null)
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val why = response?.let { "HTTP ${it.code}" } ?: t?.message ?: t?.javaClass?.simpleName
            onDown(gen, why)
        }
    }

    private fun handle(type: String, data: String) {
        val ctx = this
        when (type) {
            "summary" -> {
                val s = ApiJson.decodeFromString(Summary.serializer(), data)
                container.summaryStore.update(s)
                Notifications.updateService(ctx, s, true)
                WidgetUpdater.request(ctx)
                // Alerts that fired while this phone was offline arrive only here.
                s.alerts.forEach { Notifications.postAlertOnce(ctx, it) }
            }
            "alert" -> {
                val a = ApiJson.decodeFromString(Alert.serializer(), data)
                container.emitAlert(a)
                Notifications.postAlertOnce(ctx, a)
            }
            "kill" -> container.emitKill(ApiJson.decodeFromString(Kill.serializer(), data))
        }
    }

    @Synchronized
    private fun onDown(gen: Int, error: String?) {
        if (gen != generation) return
        container.live.down(error)
        Notifications.updateService(this, container.summaryStore.summary.value, false)
        reconnectJob?.cancel()
        val wait = backoff.nextDelayMs()
        reconnectJob = scope.launch {
            delay(wait + (0..250).random())
            if (gen == generation) connect()
        }
    }

    /**
     * Reconnects when the update rate in effect changes: the setting, or a move between Wi-Fi and
     * mobile data when they have different rates. Debounced, because a handover flips the network
     * back and forth; a change that lands back on the open stream's rate does nothing.
     */
    @OptIn(FlowPreview::class)
    private suspend fun followUpdateRate() {
        streamEvery(container.updateRate.rate, container.network.metered)
            .debounce(5_000)
            .collect { every ->
                if (openEvery > 0 && every != openEvery) {
                    Log.i(TAG, "Update rate now ${every}s (was ${openEvery}s); reconnecting")
                    reconnectNow()
                }
            }
    }

    private suspend fun watchUnreachable() {
        while (scope.isActive) {
            delay(60_000)
            val c = container.live.state.value
            val downFor = System.currentTimeMillis() - c.sinceMs
            if (!c.connected && downFor > FreshnessRules.STALE_MS && !unreachablePosted) {
                Notifications.postUnreachable(this, downFor / 60_000, c.lastError)
                unreachablePosted = true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        generation++
        source?.cancel()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        container.live.down(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"

        @Volatile
        var isRunning = false
            private set

        /** Safe to call from anywhere; a refused background start is logged, not thrown. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start the monitor service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
