package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.data.Alert
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.ui.HomeTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appContainer
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.components.Badge
import io.github.pablocollantes.cfusage.ui.components.BadgeVariant
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.EmptyState
import io.github.pablocollantes.cfusage.ui.components.LoadingBlock
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.ui.theme.statusColor
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.Times
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlertsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoadState<List<Alert>>())
    val state = _state.asStateFlow()

    init {
        // A pushed alert refreshes the history while the screen is open.
        viewModelScope.launch { c.alertEvents.collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = attempt { c.api.alerts(50) }.fold(
                { LoadState(it) },
                { LoadState(_state.value.data, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun ack(id: String) {
        viewModelScope.launch {
            attempt { c.api.ack(id) }.onSuccess { acked ->
                _state.value = _state.value.copy(data = _state.value.data?.map { if (it.id == id) acked else it })
                c.summaryStore.summary.value?.let { s -> c.summaryStore.update(s.copy(alerts = s.alerts.filterNot { it.id == id }), c.summaryStore.receivedAt.value) }
            }.onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(nav: Nav) {
    val vm = appViewModel { AlertsViewModel(it) }
    val state by vm.state.collectAsState()
    val summary by appContainer().summaryStore.summary.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    Column(Modifier.fillMaxSize()) {
        HomeTopBar("Alerts", nav, summary)
        PullToRefreshBox(state.loading && state.data != null, vm::load, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ErrorCallout(state.error, vm::load) }
                val alerts = state.data
                when {
                    alerts == null && state.loading -> item { LoadingBlock() }
                    alerts == null -> Unit
                    alerts.isEmpty() -> item {
                        EmptyState("No alerts", "You'll be notified here and on your phone when usage nears an allowance.", icon = Lucide.CircleCheck)
                    }
                    else -> items(alerts, key = { it.id }) { AlertCard(it, vm::ack, nav) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertCard(a: Alert, onAck: (String) -> Unit, nav: Nav) {
    val acked = a.ackedAt != null
    CfCard(Modifier.fillMaxWidth(), borderColor = if (!acked && a.severity == "critical") Cf.colors.destructive.copy(alpha = 0.5f) else Cf.colors.border) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sev = when (a.severity) { "critical" -> "Critical"; "warn" -> "Warning"; else -> "Notice" }
                if (a.severity == "critical" && !acked) Badge(sev, BadgeVariant.Destructive) else Badge(sev, BadgeVariant.Outline, dot = statusColor(a.severity))
                Spacer(Modifier.width(8.dp))
                Badge(a.kind.replaceFirstChar { it.uppercase() }, BadgeVariant.Secondary)
                Spacer(Modifier.weight(1f))
                val at = Times.parse(a.createdAt)
                Text(at?.let { Fmt.ago(System.currentTimeMillis() - it.toEpochMilli()) } ?: "", style = Cf.type.caption, color = Cf.colors.mutedForeground)
            }
            Spacer(Modifier.height(10.dp))
            Text(a.title, style = Cf.type.title, color = if (acked) Cf.colors.mutedForeground else Cf.colors.foreground)
            Spacer(Modifier.height(2.dp))
            Text(a.body, style = Cf.type.small, color = Cf.colors.mutedForeground)
            if (!acked || a.suggested.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!acked) CfButton("Acknowledge", { onAck(a.id) }, variant = ButtonVariant.Outline, small = true, icon = Lucide.Check)
                    a.metric?.let { CfButton("View metric", { nav.metric(it) }, variant = ButtonVariant.Ghost, small = true) }
                    for (t in a.suggested) {
                        CfButton(t.label, { nav.kill.openApply(t) }, variant = ButtonVariant.Destructive, small = true, icon = Lucide.Power)
                    }
                }
            }
            if (acked) {
                Spacer(Modifier.height(6.dp))
                Text("Acknowledged ${Times.parse(a.ackedAt)?.let { Times.dayTime(it) } ?: ""}", style = Cf.type.caption, color = Cf.colors.mutedForeground)
            }
        }
    }
}
