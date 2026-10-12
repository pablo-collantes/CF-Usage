package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.Metric
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.MetricDetail
import io.github.pablocollantes.cfusage.data.isStorage
import io.github.pablocollantes.cfusage.util.MetricText
import kotlin.math.abs
import io.github.pablocollantes.cfusage.ui.DetailTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appContainer
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.charts.AreaChart
import io.github.pablocollantes.cfusage.ui.charts.BarChart
import io.github.pablocollantes.cfusage.ui.charts.Series
import io.github.pablocollantes.cfusage.ui.components.CardContent
import io.github.pablocollantes.cfusage.ui.components.CardHeader
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.Col
import io.github.pablocollantes.cfusage.ui.components.LoadingBlock
import io.github.pablocollantes.cfusage.ui.components.ProgressBar
import io.github.pablocollantes.cfusage.ui.components.Stat
import io.github.pablocollantes.cfusage.ui.components.StatusBadge
import io.github.pablocollantes.cfusage.ui.components.TableHeader
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.ui.theme.statusColor
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.Times

class MetricDetailViewModel(private val c: AppContainer, private val key: String) : RangedLoader<MetricDetail>("cycle") {
    override suspend fun fetch(range: String) = c.api.metric(key, range)
}

@Composable
fun MetricDetailScreen(nav: Nav, key: String) {
    val vm = appViewModel(key = "metric:$key") { MetricDetailViewModel(it, key) }
    val range by vm.range.collectAsState()
    val state by vm.state.collectAsState()
    val cached = appContainer().summaryStore.summary.collectAsState().value?.metric(key)
    LaunchedEffect(Unit) { if (state.data == null) vm.load() }
    val metric = state.data?.metric ?: cached
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(metric?.label ?: MetricCatalog.label(key), nav, metric?.group)
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RangeTabs(MetricRanges, range, vm::setRange) }
            if (metric != null) item { MetricStats(metric) }
            item { ErrorCallout(state.error, vm::load) }
            val d = state.data
            if (d == null) {
                if (state.loading) item { LoadingBlock() }
            } else {
                item { UsageChartCard(d) }
                if (d.range == "cycle" && d.cumulative.isNotEmpty()) item { CumulativeCard(d) }
                item { ByResourceCard(d) }
            }
            if (metric != null) {
                item {
                    val notes = buildList {
                        if (metric.price.isNotEmpty()) add("Overage price: ${metric.price}.")
                        if (metric.sampled) add("Cloudflare samples this dataset, so counts are estimates.")
                        if (metric.isStorage) {
                            add(
                                "The figure is the current size. Storage bills as GB-months, the average of each day's peak over the cycle, " +
                                    "so the billed average trails a recent jump in size.",
                            )
                        }
                    }
                    if (notes.isNotEmpty()) {
                        Text(notes.joinToString(" "), style = Cf.type.caption, color = Cf.colors.mutedForeground)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricStats(m: Metric) {
    CfCard(Modifier.fillMaxWidth()) {
        CardContent {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.unit(m.used, m.unit), style = Cf.type.bigFigure, color = Cf.colors.foreground)
                Spacer(Modifier.width(10.dp))
                Text("of ${Fmt.unit(m.included, m.unit)}", Modifier.weight(1f), style = Cf.type.small, color = Cf.colors.mutedForeground)
                StatusBadge(m.status)
            }
            Spacer(Modifier.height(10.dp))
            ProgressBar(m.pct, statusColor(m.status).takeIf { m.status != "ok" } ?: Cf.colors.chart1)
            Spacer(Modifier.height(14.dp))
            Row {
                Stat(if (m.isStorage) "Size" else "Used", Fmt.pct(m.pct), Modifier.weight(1f))
                Stat(MetricText.projectionLabel(m), Fmt.pct(m.projectedPct), Modifier.weight(1f), sub = MetricText.short(m.projected, m.unit),
                    valueColor = if (m.projectedPct >= 1) Cf.colors.destructive else Cf.colors.foreground)
                // Storage's `used` is the current size; older servers sent the cycle average there
                // and the size as rate24h, so "Now" only appears when the two differ.
                if (!m.isStorage) {
                    Stat("Last 24h", Fmt.unit(m.rate24h, m.unit), Modifier.weight(1f))
                } else if (abs(m.rate24h - m.used) > 1e-3 * maxOf(m.used, 1e-9)) {
                    Stat("Now", Fmt.unit(m.rate24h, m.unit), Modifier.weight(1f))
                }
                Stat("Overage", Fmt.usd(m.overage.usd), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UsageChartCard(d: MetricDetail) {
    val m = d.metric
    val values = d.series.map { it.value }
    val labels = d.series.map { Times.bucketLabel(it.t, d.bucket) }
    val axis = d.series.map { Times.bucketAxisLabel(it.t, d.bucket) }
    val color = Cf.colors.chart1
    val total = values.sum()
    CfCard(Modifier.fillMaxWidth()) {
        CardHeader(
            if (m.kind == "storage") "Size" else "Usage per ${if (d.bucket == "day") "day" else "hour"}",
            if (m.kind == "storage") "Daily peak" else "${Fmt.unit(total, m.unit)} in this range",
        )
        Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 16.dp, top = 8.dp)) {
            val fmt = { v: Double -> Fmt.unit(v, m.unit) }
            if (values.size <= 48) {
                BarChart(listOf(Series(m.label, values, color)), labels, axis, format = fmt)
            } else {
                AreaChart(listOf(Series(m.label, values, color)), labels, axis, format = fmt)
            }
        }
    }
}

@Composable
private fun CumulativeCard(d: MetricDetail) {
    val m = d.metric
    CfCard(Modifier.fillMaxWidth()) {
        CardHeader("Cycle to date", "Running total against the ${Fmt.unit(m.included, m.unit)} included")
        Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 16.dp, top = 8.dp)) {
            AreaChart(
                listOf(Series("Total", d.cumulative.map { it.value }, Cf.colors.chart5)),
                d.cumulative.map { Times.bucketLabel(it.t, "day") },
                d.cumulative.map { Times.bucketAxisLabel(it.t, "day") },
                format = { Fmt.unit(it, m.unit) },
                reference = m.included to "Included",
            )
        }
    }
}

@Composable
private fun ByResourceCard(d: MetricDetail) {
    val m = d.metric
    CfCard(Modifier.fillMaxWidth()) {
        CardHeader("By resource", "${d.byResource.size} contributors in this range")
        if (d.byResource.isEmpty()) {
            Text("Nothing used in this range.", Modifier.padding(16.dp), style = Cf.type.small, color = Cf.colors.mutedForeground)
            return@CfCard
        }
        val cols = listOf(Col("Resource", 2.2f), Col("Used", 1f, end = true), Col("Share", 0.8f, end = true))
        TableHeader(cols)
        d.byResource.forEachIndexed { i, r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                Row {
                    Text(r.name, Modifier.weight(2.2f), style = Cf.type.small, color = Cf.colors.foreground, maxLines = 1)
                    Text(Fmt.unit(r.value, m.unit), Modifier.weight(1f), style = Cf.type.figure.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Cf.colors.foreground, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(Fmt.pct(r.share), Modifier.weight(0.8f), style = Cf.type.figure.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Cf.colors.mutedForeground, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Spacer(Modifier.height(5.dp))
                ProgressBar(r.share, Cf.colors.chart1.copy(alpha = 0.7f), height = 3.dp)
            }
            if (i < d.byResource.lastIndex) io.github.pablocollantes.cfusage.ui.components.Separator(Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}
