package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.data.KillTarget
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.Callout
import io.github.pablocollantes.cfusage.ui.components.CardHeader
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SegmentedTabs
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.theme.Cf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** runCatching that lets coroutine cancellation through, so a cancelled load never shows as an error. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** Remote data with its loading and error state; stale data stays visible while refreshing. */
data class LoadState<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

/** A ViewModel that loads one resource for the selected range and reloads when the range changes. */
abstract class RangedLoader<T>(initialRange: String) : ViewModel() {
    private val _range = MutableStateFlow(initialRange)
    val range: StateFlow<String> = _range.asStateFlow()
    private val _state = MutableStateFlow(LoadState<T>())
    val state: StateFlow<LoadState<T>> = _state.asStateFlow()
    private var job: Job? = null

    protected abstract suspend fun fetch(range: String): T

    fun setRange(r: String) {
        if (r == _range.value) return
        _range.value = r
        // A different range is different data: don't show the old range's numbers under the new tab.
        _state.value = LoadState(loading = true)
        load()
    }

    fun load() {
        job?.cancel()
        val r = _range.value
        _state.value = _state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            _state.value = attempt { fetch(r) }.fold(
                { LoadState(it) },
                { LoadState(_state.value.data, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }
}

val MetricRanges = listOf("24h" to "24h", "7d" to "7 days", "cycle" to "Cycle")
val WorkerRanges = MetricRanges
val SiteRanges = listOf("1h" to "1h", "24h" to "24h", "7d" to "7 days", "30d" to "30 days")

@Composable
fun RangeTabs(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    SegmentedTabs(options, selected, onSelect, modifier.fillMaxWidth())
}

@Composable
fun ErrorCallout(error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (error == null) return
    Callout(
        "Couldn't load from the usage server",
        error,
        modifier,
        icon = Lucide.WifiOff,
        destructive = true,
        action = { CfButton("Retry", onRetry, variant = ButtonVariant.Outline, small = true, icon = Lucide.Refresh) },
    )
}

private fun kindTitle(kind: String) = when (kind) {
    "waf_block" -> "Block hostname"
    "workers_dev_off" -> "workers.dev off"
    "cron_off" -> "Cron triggers off"
    "queue_pause" -> "Pause queue"
    else -> kind
}

fun killKindTitle(kind: String) = kindTitle(kind)

/** The kill switches that apply to a Worker or site, each opening the confirmation sheet. */
@Composable
fun KillActionsCard(targets: List<KillTarget>, controller: KillController, modifier: Modifier = Modifier) {
    if (targets.isEmpty()) return
    CfCard(modifier.fillMaxWidth()) {
        CardHeader("Emergency shut-off", "Runs on the server. Each one can be restored.")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            targets.forEachIndexed { i, t ->
                if (i > 0) Separator()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
                        Text(kindTitle(t.kind), style = Cf.type.caption, color = Cf.colors.mutedForeground)
                    }
                    Spacer(Modifier.width(8.dp))
                    CfButton("Shut off", { controller.openApply(t) }, variant = ButtonVariant.Destructive, small = true, icon = Lucide.Power)
                }
            }
        }
    }
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))
