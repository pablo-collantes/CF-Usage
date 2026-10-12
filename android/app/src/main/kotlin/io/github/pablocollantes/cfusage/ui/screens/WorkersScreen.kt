package io.github.pablocollantes.cfusage.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.WorkerDetail
import io.github.pablocollantes.cfusage.data.WorkerRow
import io.github.pablocollantes.cfusage.ui.DetailTopBar
import io.github.pablocollantes.cfusage.ui.HomeTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appContainer
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.charts.AreaChart
import io.github.pablocollantes.cfusage.ui.charts.BarChart
import io.github.pablocollantes.cfusage.ui.charts.BarItem
import io.github.pablocollantes.cfusage.ui.charts.BarList
import io.github.pablocollantes.cfusage.ui.charts.ChartMath
import io.github.pablocollantes.cfusage.ui.charts.Legend
import io.github.pablocollantes.cfusage.ui.charts.Series
import io.github.pablocollantes.cfusage.ui.charts.Sparkline
import io.github.pablocollantes.cfusage.ui.components.Badge
import io.github.pablocollantes.cfusage.ui.components.BadgeVariant
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.CardContent
import io.github.pablocollantes.cfusage.ui.components.CardHeader
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.EmptyState
import io.github.pablocollantes.cfusage.ui.components.FilterChip
import io.github.pablocollantes.cfusage.ui.components.LoadingBlock
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SearchField
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.components.SortMenu
import io.github.pablocollantes.cfusage.ui.components.Stat
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.Times

class WorkersViewModel(private val c: AppContainer) : RangedLoader<List<WorkerRow>>("24h") {
    override suspend fun fetch(range: String) = c.api.workers(range)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkersScreen(nav: Nav) {
    val vm = appViewModel { WorkersViewModel(it) }
    val range by vm.range.collectAsState()
    val state by vm.state.collectAsState()
    val summary by appContainer().summaryStore.summary.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(KindFilter.All) }
    var sort by rememberSaveable { mutableStateOf(WorkerSort.Requests) }
    var desc by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { if (state.data == null) vm.load() }
    val rows = state.data?.let { filterWorkers(it, query, kind, sort, desc) }

    Column(Modifier.fillMaxSize()) {
        HomeTopBar("Workers", nav, summary)
        PullToRefreshBox(state.loading && state.data != null, vm::load, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { RangeTabs(WorkerRanges, range, vm::setRange) }
                item { SearchField(query, { query = it }, "Filter by name or hostname", Modifier.fillMaxWidth()) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in KindFilter.entries) FilterChip(k.label, kind == k, { kind = k })
                        Spacer(Modifier.weight(1f))
                        SortMenu(WorkerSort.entries.map { it to it.label }, sort, desc, { s, d -> sort = s; desc = d })
                    }
                }
                item { ErrorCallout(state.error, vm::load) }
                when {
                    rows == null && state.loading -> item { LoadingBlock() }
                    rows == null -> Unit
                    rows.isEmpty() -> item {
                        EmptyState("No matching Workers", if (query.isNotEmpty()) "Nothing matches \"$query\"." else "No Worker traffic in this range.", icon = Lucide.Server)
                    }
                    else -> {
                        item {
                            Text(
                                "${rows.size} scripts · ${Fmt.compact(rows.sumOf { it.requests })} requests · ${Fmt.ms(rows.sumOf { it.cpuMs })} CPU",
                                style = Cf.type.caption,
                                color = Cf.colors.mutedForeground,
                            )
                        }
                        item {
                            CfCard(Modifier.fillMaxWidth()) {
                                rows.forEachIndexed { i, r ->
                                    WorkerListRow(r) { nav.worker(r.name) }
                                    if (i < rows.lastIndex) Separator(Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerListRow(r: WorkerRow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name.removePrefix("pages:"), Modifier.weight(1f, fill = false), style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.kind == "pages") {
                    Spacer(Modifier.width(6.dp))
                    Badge("Pages", BadgeVariant.Outline)
                }
            }
            val err = if (r.errors > 0) " · ${Fmt.pct(r.errorRate)} errors" else ""
            Text(
                "${Fmt.ms(r.cpuMsPerRequest)}/req$err",
                style = Cf.type.caption,
                color = if (r.errorRate >= 0.05) Cf.colors.destructive else Cf.colors.mutedForeground,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Sparkline(r.spark, Cf.colors.chart1, Modifier.width(64.dp).height(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
            Text(Fmt.compact(r.requests), style = Cf.type.figure, color = Cf.colors.foreground, textAlign = TextAlign.End)
            Text(Fmt.pct(r.shareOfRequests), style = Cf.type.caption, color = Cf.colors.mutedForeground)
        }
    }
}

class WorkerDetailViewModel(private val c: AppContainer, private val name: String) : RangedLoader<WorkerDetail>("24h") {
    override suspend fun fetch(range: String) = c.api.worker(name, range)
}

@Composable
fun WorkerDetailScreen(nav: Nav, name: String) {
    val vm = appViewModel(key = "worker:$name") { WorkerDetailViewModel(it, name) }
    val range by vm.range.collectAsState()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { if (state.data == null) vm.load() }
    val d = state.data
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(name.removePrefix("pages:"), nav, if (name.startsWith("pages:")) "Pages Functions" else "Worker")
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RangeTabs(WorkerRanges, range, vm::setRange) }
            item { ErrorCallout(state.error, vm::load) }
            if (d == null) {
                if (state.loading) item { LoadingBlock() }
                return@LazyColumn
            }
            val r = d.row
            item {
                CfCard(Modifier.fillMaxWidth()) {
                    CardContent {
                        Row {
                            Stat("Requests", Fmt.compact(r.requests), Modifier.weight(1f), sub = "${Fmt.pct(r.shareOfRequests)} of account")
                            Stat("Errors", Fmt.compact(r.errors), Modifier.weight(1f), sub = Fmt.pct(r.errorRate),
                                valueColor = if (r.errorRate >= 0.05) Cf.colors.destructive else Cf.colors.foreground)
                            Stat("Subrequests", Fmt.compact(r.subrequests), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row {
                            Stat("CPU time", Fmt.ms(r.cpuMs), Modifier.weight(1f))
                            Stat("CPU / request", Fmt.ms(r.cpuMsPerRequest), Modifier.weight(1f))
                            Stat("Hosts", r.hosts.size.toString(), Modifier.weight(1f))
                        }
                    }
                }
            }
            if (d.series.isNotEmpty()) {
                item {
                    val labels = d.series.map { Times.bucketLabel(it.t, d.bucket) }
                    val axis = d.series.map { Times.bucketAxisLabel(it.t, d.bucket) }
                    val series = listOf(
                        Series("Requests", d.series.map { it.requests }, Cf.colors.chart1),
                        Series("Errors", d.series.map { it.errors }, Cf.colors.chart4),
                    )
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("Requests", "Per ${if (d.bucket == "day") "day" else "hour"}")
                        Column(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                            AreaChart(series, labels, axis)
                            Legend(series)
                        }
                    }
                }
                item {
                    // 7 days of hourly bars are too thin to read; sum them into 6-hour bars.
                    val cpu = d.series.map { it.cpuMs }
                    val n = if (cpu.size > 48) 28 else cpu.size
                    val starts = ChartMath.bucketStarts(cpu.size, n).map { d.series[it].t }
                    val labels = starts.map { Times.bucketLabel(it, d.bucket) }
                    val axis = starts.map { Times.bucketAxisLabel(it, d.bucket) }
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("CPU time", "Billed against the 30M ms allowance")
                        Column(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                            BarChart(listOf(Series("CPU", ChartMath.downsampleSum(cpu, n), Cf.colors.chart5)), labels, axis, format = Fmt::ms)
                        }
                    }
                }
            }
            if (d.colos.isNotEmpty()) {
                item {
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("Data centres", "Where Cloudflare ran it")
                        BarList(
                            d.colos.map { BarItem(it.colo, it.requests, Fmt.compact(it.requests)) },
                            Cf.colors.chart1,
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }
            val site = d.site
            if (site != null) {
                item {
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("Visitors to ${site.row.host}", "Zone analytics for its custom domain")
                        Column(Modifier.padding(16.dp)) {
                            BarList(
                                site.countries.take(6).map { BarItem(countryName(it.country), it.requests, Fmt.compact(it.requests), io.github.pablocollantes.cfusage.util.flagEmoji(it.country)) },
                                Cf.colors.chart2,
                            )
                            Spacer(Modifier.height(12.dp))
                            BarList(site.paths.take(6).map { BarItem(it.path, it.requests, Fmt.compact(it.requests)) }, Cf.colors.chart1)
                            Spacer(Modifier.height(12.dp))
                            CfButton("Open site traffic", { nav.site(site.row.host) }, variant = ButtonVariant.Outline, icon = Lucide.Globe, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } else if (r.hosts.isNotEmpty()) {
                item {
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("Custom domains")
                        r.hosts.forEachIndexed { i, h ->
                            Row(Modifier.fillMaxWidth().clickable { nav.site(h) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(h, Modifier.weight(1f), style = Cf.type.small, color = Cf.colors.foreground)
                                androidx.compose.material3.Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = Cf.colors.mutedForeground)
                            }
                            if (i < r.hosts.lastIndex) Separator(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
            item { KillActionsCard(r.killable, nav.kill) }
        }
    }
}

/** Display name for an ISO country code, falling back to the code itself. */
fun countryName(code: String): String =
    runCatching { java.util.Locale("", code).displayCountry.takeIf { it.isNotBlank() && it != code } }.getOrNull() ?: code
