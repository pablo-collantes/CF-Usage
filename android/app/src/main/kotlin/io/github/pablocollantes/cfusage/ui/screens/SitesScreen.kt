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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.SiteDetail
import io.github.pablocollantes.cfusage.data.SiteRow
import io.github.pablocollantes.cfusage.ui.DetailTopBar
import io.github.pablocollantes.cfusage.ui.HomeTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appContainer
import io.github.pablocollantes.cfusage.ui.appViewModel
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
import io.github.pablocollantes.cfusage.ui.components.Col
import io.github.pablocollantes.cfusage.ui.components.EmptyState
import io.github.pablocollantes.cfusage.ui.components.FilterChip
import io.github.pablocollantes.cfusage.ui.components.LoadingBlock
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SearchField
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.components.SortMenu
import io.github.pablocollantes.cfusage.ui.components.Stat
import io.github.pablocollantes.cfusage.ui.components.TableHeader
import io.github.pablocollantes.cfusage.ui.components.TableRow
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.util.Fmt
import io.github.pablocollantes.cfusage.util.Times
import io.github.pablocollantes.cfusage.util.flagEmoji

class SitesViewModel(private val c: AppContainer) : RangedLoader<List<SiteRow>>("24h") {
    override suspend fun fetch(range: String) = c.api.sites(range)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen(nav: Nav) {
    val vm = appViewModel { SitesViewModel(it) }
    val range by vm.range.collectAsState()
    val state by vm.state.collectAsState()
    val summary by appContainer().summaryStore.summary.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf(ServiceFilter.All) }
    var sort by rememberSaveable { mutableStateOf(SiteSort.Requests) }
    var desc by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { if (state.data == null) vm.load() }
    val rows = state.data?.let { filterSites(it, query, service, sort, desc) }

    Column(Modifier.fillMaxSize()) {
        HomeTopBar("Sites", nav, summary)
        PullToRefreshBox(state.loading && state.data != null, vm::load, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { RangeTabs(SiteRanges, range, vm::setRange) }
                item { SearchField(query, { query = it }, "Filter by hostname or service", Modifier.fillMaxWidth()) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (s in ServiceFilter.entries) FilterChip(s.label, service == s, { service = s })
                        }
                        Spacer(Modifier.width(8.dp))
                        SortMenu(SiteSort.entries.map { it to it.label }, sort, desc, { s, d -> sort = s; desc = d })
                    }
                }
                item { ErrorCallout(state.error, vm::load) }
                when {
                    rows == null && state.loading -> item { LoadingBlock() }
                    rows == null -> Unit
                    rows.isEmpty() -> item {
                        EmptyState("No matching sites", if (query.isNotEmpty()) "Nothing matches \"$query\"." else "No visitor traffic in this range.", icon = Lucide.Globe)
                    }
                    else -> {
                        item {
                            Text(
                                "${rows.size} hosts · ${Fmt.compact(rows.sumOf { it.requests })} requests · ${Fmt.bytes(rows.sumOf { it.bytes })}",
                                style = Cf.type.caption,
                                color = Cf.colors.mutedForeground,
                            )
                        }
                        item {
                            CfCard(Modifier.fillMaxWidth()) {
                                rows.forEachIndexed { i, r ->
                                    SiteListRow(r, sort) { nav.site(r.host) }
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
private fun SiteListRow(r: SiteRow, sort: SiteSort, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.host, style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                r.service?.let {
                    Badge(it.substringBefore(':').replaceFirstChar { c -> c.uppercase() } + " · " + it.substringAfter(':'), BadgeVariant.Secondary)
                    Spacer(Modifier.width(6.dp))
                }
                val detail = when (sort) {
                    SiteSort.Bytes -> Fmt.bytes(r.bytes)
                    SiteSort.Blocked -> "${Fmt.compact(r.blocked)} blocked"
                    SiteSort.Cached -> "${Fmt.pct(r.cachedShare)} cached"
                    SiteSort.Status4xx -> "${Fmt.pct(r.status4xx)} 4xx"
                    SiteSort.Status5xx -> "${Fmt.pct(r.status5xx)} 5xx"
                    else -> if (r.blocked > 0) "${Fmt.compact(r.blocked)} blocked" else Fmt.bytes(r.bytes)
                }
                Text(detail, style = Cf.type.caption, color = Cf.colors.mutedForeground, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        Sparkline(r.spark, Cf.colors.chart2, Modifier.width(64.dp).height(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(Fmt.compact(r.requests), Modifier.width(56.dp), style = Cf.type.figure, color = Cf.colors.foreground, textAlign = TextAlign.End)
    }
}

class SiteDetailViewModel(private val c: AppContainer, private val host: String) : RangedLoader<SiteDetail>("24h") {
    override suspend fun fetch(range: String) = c.api.site(host, range)
}

@Composable
fun SiteDetailScreen(nav: Nav, host: String) {
    val vm = appViewModel(key = "site:$host") { SiteDetailViewModel(it, host) }
    val range by vm.range.collectAsState()
    val state by vm.state.collectAsState()
    var pathFilter by rememberSaveable { mutableStateOf("") }
    var allCountries by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.data == null) vm.load() }
    val d = state.data
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(host, nav, d?.row?.service)
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RangeTabs(SiteRanges, range, vm::setRange) }
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
                            Stat("Requests", Fmt.compact(r.requests), Modifier.weight(1f))
                            Stat("Bandwidth", Fmt.bytes(r.bytes), Modifier.weight(1f))
                            Stat("Blocked", Fmt.compact(r.blocked), Modifier.weight(1f), valueColor = if (r.blocked > 0) Cf.colors.destructive else Cf.colors.foreground)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row {
                            Stat("Cached", Fmt.pct(r.cachedShare), Modifier.weight(1f))
                            Stat("4xx", Fmt.pct(r.status4xx), Modifier.weight(1f))
                            Stat("5xx", Fmt.pct(r.status5xx), Modifier.weight(1f), valueColor = if (r.status5xx >= 0.02) Cf.colors.destructive else Cf.colors.foreground)
                        }
                    }
                }
            }
            if (d.series.isNotEmpty()) {
                item {
                    // Stacked bars: allowed traffic underneath, WAF-blocked on top. At most 48 bars.
                    val n = d.series.size.coerceAtMost(48)
                    val allowed = ChartMath.downsampleSum(d.series.map { (it.requests - it.blocked).coerceAtLeast(0.0) }, n)
                    val blocked = ChartMath.downsampleSum(d.series.map { it.blocked }, n)
                    val starts = ChartMath.bucketStarts(d.series.size, n).map { d.series[it].t }
                    val labels = starts.map { Times.bucketLabel(it, d.bucket) }
                    val axis = starts.map { Times.bucketAxisLabel(it, d.bucket) }
                    val series = listOf(Series("Allowed", allowed, Cf.colors.chart2), Series("Blocked", blocked, Cf.colors.chart4))
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("Requests", "Allowed vs blocked by the WAF")
                        Column(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                            BarChart(series, labels, axis)
                            Legend(series)
                        }
                    }
                }
            }
            item {
                val paths = d.paths.filter { pathFilter.isBlank() || pathFilter.trim().lowercase() in it.path.lowercase() }
                val cols = listOf(Col("Path", 3f), Col("Requests", 1f, end = true))
                CfCard(Modifier.fillMaxWidth()) {
                    CardHeader("Top paths", "${d.paths.size} most requested")
                    SearchField(pathFilter, { pathFilter = it }, "Filter paths", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                    TableHeader(cols)
                    if (paths.isEmpty()) {
                        Text("No paths match.", Modifier.padding(16.dp), style = Cf.type.small, color = Cf.colors.mutedForeground)
                    }
                    paths.forEachIndexed { i, p ->
                        TableRow(listOf(p.path to cols[0], Fmt.compact(p.requests) to cols[1]), last = i == paths.lastIndex)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (d.countries.isNotEmpty()) {
                item {
                    val shown = if (allCountries) d.countries else d.countries.take(8)
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader("Countries", "Where visitors are")
                        Column(Modifier.padding(16.dp)) {
                            BarList(shown.map { BarItem(countryName(it.country), it.requests, Fmt.compact(it.requests), flagEmoji(it.country)) }, Cf.colors.chart1)
                            if (d.countries.size > 8) {
                                Spacer(Modifier.height(8.dp))
                                CfButton(
                                    if (allCountries) "Show fewer" else "Show all ${d.countries.size}",
                                    { allCountries = !allCountries },
                                    variant = ButtonVariant.Ghost,
                                    small = true,
                                )
                            }
                        }
                    }
                }
            }
            item {
                BreakdownCard("Status codes", d.statuses.map { BarItem(it.status.toString(), it.requests, Fmt.compact(it.requests)) }, Cf.colors.chart3)
            }
            item {
                BreakdownCard("Security actions", d.securityActions.map { BarItem(it.action.ifBlank { "none" }, it.requests, Fmt.compact(it.requests)) }, Cf.colors.chart4)
            }
            item {
                BreakdownCard("Browsers", d.userAgents.map { BarItem(it.agent.ifBlank { "unknown" }, it.requests, Fmt.compact(it.requests)) }, Cf.colors.chart5)
            }
            item {
                BreakdownCard("Cache status", d.cache.map { BarItem(it.status.ifBlank { "none" }, it.requests, Fmt.compact(it.requests)) }, Cf.colors.chart2)
            }
            item { KillActionsCard(r.killable, nav.kill) }
        }
    }
}

@Composable
private fun BreakdownCard(title: String, items: List<BarItem>, color: androidx.compose.ui.graphics.Color) {
    if (items.isEmpty()) return
    CfCard(Modifier.fillMaxWidth()) {
        CardHeader(title)
        BarList(items, color, Modifier.padding(16.dp))
    }
}
