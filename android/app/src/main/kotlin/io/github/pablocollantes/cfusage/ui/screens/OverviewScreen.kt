package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.byProduct
import io.github.pablocollantes.cfusage.data.worstStatus
import io.github.pablocollantes.cfusage.util.MetricText
import io.github.pablocollantes.cfusage.ui.components.BadgeShape
import io.github.pablocollantes.cfusage.ui.components.CardShape
import io.github.pablocollantes.cfusage.ui.components.ControlShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.pablocollantes.cfusage.ui.HomeTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.charts.RadialProgress
import io.github.pablocollantes.cfusage.ui.charts.Sparkline
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.Callout
import io.github.pablocollantes.cfusage.ui.components.CardContent
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.EmptyState
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.ProgressBar
import io.github.pablocollantes.cfusage.ui.components.SectionLabel
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.components.StatusBadge
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.ui.theme.statusColor
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.Times
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OverviewViewModel(private val c: AppContainer) : androidx.lifecycle.ViewModel() {
    val summary = c.summaryStore.summary
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Pull to refresh: fetch the summary directly, without waiting for the next pushed one. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            attempt { c.api.summary() }
                .onSuccess { c.summaryStore.update(it); _error.value = null }
                .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(nav: Nav) {
    val vm = appViewModel { OverviewViewModel(it) }
    val summary by vm.summary.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val error by vm.error.collectAsState()
    Column(Modifier.fillMaxSize()) {
        HomeTopBar("Overview", nav, summary)
        PullToRefreshBox(refreshing, vm::refresh, Modifier.fillMaxSize()) {
            val s = summary
            if (s == null) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { ErrorCallout(error, vm::refresh, Modifier.padding(16.dp)) }
                    item {
                        EmptyState(
                            "No usage data yet",
                            "The app is connecting to the usage server. Check the server address in Settings if this persists.",
                            icon = Lucide.Activity,
                            action = { CfButton("Open settings", nav::settings, variant = ButtonVariant.Outline) },
                        )
                    }
                }
            } else {
                OverviewContent(s, error, nav, vm::refresh)
            }
        }
    }
}

@Composable
private fun OverviewContent(s: Summary, error: String?, nav: Nav, retry: () -> Unit) {
    var showUnused by rememberSaveable { mutableStateOf(false) }
    val products = s.byProduct()
    val (inUse, unused) = products.partition { (_, ms) -> ms.any { it.used > 0 } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) item { ErrorCallout(error, retry) }
        if (s.pollError != null) {
            item { Callout("The server's last poll failed", s.pollError, icon = Lucide.TriangleAlert, destructive = true) }
        }
        if (s.kills.isNotEmpty()) {
            item {
                Callout(
                    "${s.kills.size} kill switch${if (s.kills.size == 1) "" else "es"} on",
                    s.kills.joinToString("\n") { "${it.label} · ${it.state}" },
                    icon = Lucide.Power,
                    destructive = true,
                    onClick = nav::killSwitches,
                )
            }
        }
        if (s.alerts.isNotEmpty()) {
            item {
                val worst = s.alerts.first()
                Callout(
                    if (s.alerts.size == 1) worst.title else "${s.alerts.size} alerts · ${worst.title}",
                    worst.body,
                    icon = Lucide.TriangleAlert,
                    destructive = worst.severity == "critical",
                    onClick = { nav.tab("alerts") },
                )
            }
        }
        item { CycleAndBill(s) }
        item { SectionLabel("Usage by product") }
        for ((group, metrics) in inUse) {
            item(key = "product-$group") { ProductCard(group, metrics, nav) }
        }
        if (unused.isNotEmpty()) {
            item(key = "unused") {
                NotInUse(unused.map { MetricCatalog.groupShort(it.first) }, showUnused) { showUnused = !showUnused }
            }
            if (showUnused) {
                for ((group, metrics) in unused) {
                    item(key = "product-$group") { ProductCard(group, metrics, nav) }
                }
            }
        }
        item {
            Text(
                "Data as of ${Times.parse(s.dataAsOf)?.let { Times.dayTime(it) } ?: "–"} · Cloudflare analytics lag 1–4 min",
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = Cf.type.caption,
                color = Cf.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun CycleAndBill(s: Summary) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CfCard(Modifier.weight(1f).fillMaxHeight()) {
            CardContent {
                Text("Billing cycle", style = Cf.type.caption, color = Cf.colors.mutedForeground)
                Text("${Times.utcDate(s.cycle.start)} → ${Times.utcDate(s.cycle.end)}", style = Cf.type.title, color = Cf.colors.foreground)
                Spacer(Modifier.height(10.dp))
                ProgressBar(s.cycle.elapsed, Cf.colors.foreground.copy(alpha = 0.8f), height = 4.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Fmt.pct(s.cycle.elapsed)} elapsed · ${"%.1f".format(s.cycle.daysLeft)} days left",
                    style = Cf.type.caption,
                    color = Cf.colors.mutedForeground,
                )
            }
        }
        CfCard(Modifier.weight(1f).fillMaxHeight()) {
            CardContent {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Estimated bill", Modifier.weight(1f), style = Cf.type.caption, color = Cf.colors.mutedForeground)
                    StatusBadge(s.status)
                }
                Text(Fmt.usd(s.estimatedBillUsd), style = Cf.type.bigFigure, color = Cf.colors.foreground)
                val overage = (s.estimatedBillUsd - 5.0).coerceAtLeast(0.0)
                Text(
                    if (overage > 0) "$5 base + ${Fmt.usd(overage)} projected overage" else "$5 base, nothing billable projected",
                    style = Cf.type.caption,
                    color = if (overage > 0) Cf.colors.destructive else Cf.colors.mutedForeground,
                )
            }
        }
    }
}

/**
 * One product: a ring per metric in use for a quick read, then a row per metric with used /
 * included, the percent and the projection (the billed average for storage).
 */
@Composable
private fun ProductCard(group: String, metrics: List<Metric>, nav: Nav) {
    val worst = worstStatus(metrics)
    val overage = metrics.sumOf { it.overage.usd }
    val (active, idle) = metrics.partition { it.used > 0 }
    CfCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group, Modifier.weight(1f), style = Cf.type.title, color = Cf.colors.foreground, maxLines = 1)
            if (overage > 0) {
                Text("+${Fmt.usd(overage)}", style = Cf.type.caption.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.destructive)
                Spacer(Modifier.width(8.dp))
            }
            StatusBadge(worst)
        }
        if (active.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (m in active) RingTile(m) { nav.metric(m.key) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Separator()
        val rows = active.ifEmpty { metrics }
        rows.forEachIndexed { i, m ->
            ProductRow(m) { nav.metric(m.key) }
            if (i < rows.lastIndex) Separator(Modifier.padding(start = 16.dp))
        }
        if (active.isNotEmpty() && idle.isNotEmpty()) {
            Separator(Modifier.padding(start = 16.dp))
            Text(
                "Not used: " + idle.joinToString(", ") { MetricCatalog.name(it.key) },
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = Cf.type.caption,
                color = Cf.colors.mutedForeground,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RingTile(m: Metric, onClick: () -> Unit) {
    val color = statusColor(m.status).takeIf { m.status != "ok" } ?: Cf.colors.chart1
    Column(
        Modifier.width(62.dp).clip(ControlShape).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RadialProgress(m.pct, color, size = 44.dp, stroke = 5.dp) {
            Text(Fmt.pct(m.pct), style = Cf.type.micro.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp), color = Cf.colors.foreground, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(MetricCatalog.short(m.key), style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.mutedForeground, maxLines = 1)
    }
}

@Composable
private fun ProductRow(m: Metric, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodeBadge(MetricCatalog.short(m.key))
            Spacer(Modifier.width(10.dp))
            Text(MetricCatalog.name(m.key), Modifier.weight(1f), style = Cf.type.small, color = Cf.colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(MetricText.ofTotal(m), style = Cf.type.figure.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal), color = Cf.colors.mutedForeground, maxLines = 1)
            Text(
                Fmt.pct(m.pct),
                Modifier.width(48.dp),
                style = Cf.type.figure.copy(fontSize = 13.sp),
                color = Cf.colors.foreground,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        Text(
            MetricText.projectionLine(m),
            Modifier.padding(start = 42.dp, top = 2.dp),
            style = Cf.type.caption,
            color = if (m.projectedPct >= 1) Cf.colors.destructive else Cf.colors.mutedForeground,
            maxLines = 1,
        )
    }
}

/** The widget code for a metric ("rq", "R", "W", "S") as a small muted badge. */
@Composable
fun CodeBadge(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(min = 32.dp).clip(BadgeShape).background(Cf.colors.muted).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(code, style = Cf.type.caption.copy(fontWeight = FontWeight.SemiBold), color = Cf.colors.foreground, maxLines = 1)
    }
}

@Composable
private fun NotInUse(names: List<String>, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).border(1.dp, Cf.colors.border, CardShape).clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Not in use: ${names.joinToString(", ")}", Modifier.weight(1f), style = Cf.type.small, color = Cf.colors.mutedForeground)
        androidx.compose.material3.Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, null, Modifier.size(16.dp), tint = Cf.colors.mutedForeground)
    }
}
