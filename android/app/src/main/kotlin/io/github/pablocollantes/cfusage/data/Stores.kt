package io.github.pablocollantes.cfusage.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.pablocollantes.cfusage.BuildConfig
import io.github.pablocollantes.cfusage.widget.WidgetDesignStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.File

private val Context.prefs by preferencesDataStore("cfusage")

/** App-local preferences. Alert thresholds and the cycle day live on the server instead. */
class Prefs(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val monitorKey = booleanPreferencesKey("monitor_enabled")

    val serverUrl: Flow<String> = context.prefs.data.map { it[serverUrlKey] ?: BuildConfig.DEFAULT_SERVER_URL }
    val monitorEnabled: Flow<Boolean> = context.prefs.data.map { it[monitorKey] ?: true }

    suspend fun serverUrlNow() = serverUrl.first()
    suspend fun monitorEnabledNow() = monitorEnabled.first()

    suspend fun setServerUrl(url: String) = context.prefs.edit { it[serverUrlKey] = url.trim().trimEnd('/') }
    suspend fun setMonitorEnabled(on: Boolean) = context.prefs.edit { it[monitorKey] = on }
}

@Serializable
private data class StoredSummary(val receivedAtMs: Long, val summary: Summary)

/**
 * The newest Summary, persisted to a file so the app and the widgets start instantly with the
 * last known numbers, even offline.
 */
class SummaryStore(context: Context) {
    private val file = File(context.filesDir, "summary.json")
    private val _summary = MutableStateFlow<Summary?>(null)
    private val _receivedAt = MutableStateFlow(0L)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()
    /** When this device last received a summary (from the stream or a fetch). */
    val receivedAt: StateFlow<Long> = _receivedAt.asStateFlow()

    init {
        runCatching {
            if (file.exists()) {
                val stored = ApiJson.decodeFromString(StoredSummary.serializer(), file.readText())
                _summary.value = stored.summary
                _receivedAt.value = stored.receivedAtMs
            }
        }
    }

    @Synchronized
    fun update(summary: Summary, receivedAtMs: Long = System.currentTimeMillis()) {
        _summary.value = summary
        _receivedAt.value = receivedAtMs
        val tmp = File(file.parentFile, "summary.json.tmp")
        runCatching {
            tmp.writeText(ApiJson.encodeToString(StoredSummary.serializer(), StoredSummary(receivedAtMs, summary)))
            tmp.renameTo(file)
        }
    }

    /** Fold a kill state change into the cached summary, so banners update without waiting a poll. */
    fun applyKill(kill: Kill) {
        val s = _summary.value ?: return
        val others = s.kills.filterNot { it.id == kill.id }
        val kills = if (kill.state == "restored") others else listOf(kill) + others
        update(s.copy(kills = kills), _receivedAt.value)
    }
}

/** Whether the monitor service's event stream is open right now. */
data class Connection(val connected: Boolean, val sinceMs: Long, val lastError: String? = null)

class LiveStatus {
    private val _state = MutableStateFlow(Connection(false, System.currentTimeMillis()))
    val state: StateFlow<Connection> = _state.asStateFlow()

    fun up() {
        if (!_state.value.connected) _state.value = Connection(true, System.currentTimeMillis())
    }

    fun down(error: String?) {
        val cur = _state.value
        _state.value = if (cur.connected) Connection(false, System.currentTimeMillis(), error) else cur.copy(lastError = error)
    }
}

/** Process-wide singletons. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val prefs = Prefs(context)
    val summaryStore = SummaryStore(context)
    val designStore = WidgetDesignStore(context)
    val live = LiveStatus()
    val api = ApiClient(baseUrl = { prefs.serverUrlNow() })
    val updateRate = UpdateRateStore(context)
    val network = NetworkState(context)

    /** Seconds between pushed summaries right now: the update rate for the current network. */
    fun streamEverySeconds(): Int = updateRate.rate.value.effectiveSeconds(network.metered.value)

    private val _killEvents = MutableSharedFlow<Kill>(extraBufferCapacity = 16)
    val killEvents: SharedFlow<Kill> = _killEvents

    private val _alertEvents = MutableSharedFlow<Alert>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<Alert> = _alertEvents

    fun emitKill(kill: Kill) {
        summaryStore.applyKill(kill)
        _killEvents.tryEmit(kill)
    }

    fun emitAlert(alert: Alert) {
        _alertEvents.tryEmit(alert)
    }
}
