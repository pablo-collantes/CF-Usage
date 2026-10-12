package io.github.pablocollantes.cfusage.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pablocollantes.cfusage.CfUsageApp
import io.github.pablocollantes.cfusage.container
import io.github.pablocollantes.cfusage.data.AppContainer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.ui.components.IconButton
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.Pill
import io.github.pablocollantes.cfusage.ui.components.ScreenSurface
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.screens.AlertsScreen
import io.github.pablocollantes.cfusage.ui.screens.KillScreen
import io.github.pablocollantes.cfusage.ui.screens.KillSheetHost
import io.github.pablocollantes.cfusage.ui.screens.KillController
import io.github.pablocollantes.cfusage.ui.screens.MetricDetailScreen
import io.github.pablocollantes.cfusage.ui.screens.OverviewScreen
import io.github.pablocollantes.cfusage.ui.screens.SettingsScreen
import io.github.pablocollantes.cfusage.ui.screens.SiteDetailScreen
import io.github.pablocollantes.cfusage.ui.screens.SitesScreen
import io.github.pablocollantes.cfusage.ui.screens.WorkerDetailScreen
import io.github.pablocollantes.cfusage.ui.screens.WorkersScreen
import io.github.pablocollantes.cfusage.ui.screens.WidgetEditorScreen
import io.github.pablocollantes.cfusage.ui.screens.WidgetsScreen
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.util.Freshness
import io.github.pablocollantes.cfusage.util.FreshnessLevel
import io.github.pablocollantes.cfusage.util.FreshnessRules
import io.github.pablocollantes.cfusage.util.Times
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import io.github.pablocollantes.cfusage.data.KillTarget

/** A notification tap: which tab to open, optionally a kill switch to review. */
data class DeepLink(val route: String, val kill: KillTarget?, val nonce: Long, val arg: String? = null)

/** Navigation callbacks handed to every screen. */
class Nav(private val nav: NavHostController, val kill: KillController) {
    fun back() = nav.popBackStack()
    fun metric(key: String) = nav.navigate("metric/${Uri.encode(key)}")
    fun worker(name: String) = nav.navigate("worker/${Uri.encode(name)}")
    fun site(host: String) = nav.navigate("site/${Uri.encode(host)}")
    fun killSwitches() = nav.navigate("kill") { launchSingleTop = true }
    fun settings() = nav.navigate("settings") { launchSingleTop = true }
    fun widget(id: String) = nav.navigate("widget/${Uri.encode(id)}")
    fun tab(route: String) = nav.navigate(route) {
        popUpTo("overview") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("overview", "Overview", Lucide.Dashboard),
    Tab("workers", "Workers", Lucide.Server),
    Tab("sites", "Sites", Lucide.Globe),
    Tab("alerts", "Alerts", Lucide.Bell),
    Tab("widgets", "Widgets", Lucide.LayoutGrid),
)

@Composable
fun AppRoot(deepLinks: StateFlow<DeepLink?>, onDeepLinkHandled: () -> Unit) {
    val navController = rememberNavController()
    val killController = appViewModel { KillController(it) }
    val nav = remember(navController, killController) { Nav(navController, killController) }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val link by deepLinks.collectAsState()

    LaunchedEffect(link?.nonce) {
        val l = link ?: return@LaunchedEffect
        when (l.route) {
            "alerts" -> nav.tab("alerts")
            "metric" -> l.arg?.let { nav.metric(it) }
            "widget" -> {
                nav.tab("widgets")
                l.arg?.let { nav.widget(it) }
            }
            "kill" -> {
                nav.killSwitches()
                l.kill?.let { killController.openApply(it) }
            }
        }
        onDeepLinkHandled()
    }

    ScreenSurface {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                NavHost(navController, startDestination = "overview") {
                    composable("overview") { OverviewScreen(nav) }
                    composable("workers") { WorkersScreen(nav) }
                    composable("sites") { SitesScreen(nav) }
                    composable("alerts") { AlertsScreen(nav) }
                    composable("kill") { KillScreen(nav) }
                    composable("settings") { SettingsScreen(nav) }
                    composable("widgets") { WidgetsScreen(nav) }
                    composable("widget/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                        WidgetEditorScreen(nav, it.arguments?.getString("id").orEmpty())
                    }
                    composable("metric/{key}", listOf(navArgument("key") { type = NavType.StringType })) {
                        MetricDetailScreen(nav, it.arguments?.getString("key").orEmpty())
                    }
                    composable("worker/{name}", listOf(navArgument("name") { type = NavType.StringType })) {
                        WorkerDetailScreen(nav, it.arguments?.getString("name").orEmpty())
                    }
                    composable("site/{host}", listOf(navArgument("host") { type = NavType.StringType })) {
                        SiteDetailScreen(nav, it.arguments?.getString("host").orEmpty())
                    }
                }
            }
            if (Tabs.any { it.route == route }) BottomBar(route, nav)
        }
        KillSheetHost(killController)
    }
}

@Composable
fun appContainer(): AppContainer = LocalContext.current.container

/** A ViewModel built from the app container; [key] separates instances (one per worker, site…). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (AppContainer) -> VM): VM =
    viewModel(key = key) { create((this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CfUsageApp).container) }

@Composable
private fun BottomBar(route: String?, nav: Nav) {
    val alertCount = appContainer().summaryStore.summary.collectAsState().value?.alerts?.size ?: 0
    Column(Modifier.background(Cf.colors.background)) {
        Separator()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)) {
            for (tab in Tabs) {
                val active = tab.route == route
                val tint = if (active) Cf.colors.foreground else Cf.colors.mutedForeground
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { nav.tab(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Box {
                        Icon(tab.icon, null, Modifier.size(20.dp), tint = tint)
                        if (tab.route == "alerts" && alertCount > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(start = 12.dp)
                                    .size(8.dp).background(Cf.colors.chart4, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, style = Cf.type.micro.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium), color = tint)
                }
            }
        }
    }
}

/** Live/stale pill, recomputed every 15 s so "3 min ago" keeps counting without new data. */
@Composable
fun rememberFreshness(): Freshness {
    val c = appContainer()
    val summary by c.summaryStore.summary.collectAsState()
    val conn by c.live.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val rate by c.updateRate.rate.collectAsState()
    val metered by c.network.metered.collectAsState()
    return FreshnessRules.of(
        maxOf(now, System.currentTimeMillis()),
        Times.epochMs(summary?.lastPollAt),
        conn.connected,
        summary?.pollError,
        everyMs = rate.effectiveSeconds(metered) * 1000L,
    )
}

@Composable
fun FreshnessPill(onClick: (() -> Unit)? = null) {
    val f = rememberFreshness()
    val color = when (f.level) {
        FreshnessLevel.LIVE -> Cf.colors.chart2
        FreshnessLevel.RECENT -> Cf.colors.chart3
        else -> Cf.colors.chart4
    }
    Pill(f.label, color, onClick = onClick)
}

/** Header for the four tabs: title, freshness pill, kill switches and settings. */
@Composable
fun HomeTopBar(title: String, nav: Nav, summary: Summary? = null) {
    val activeKills = summary?.kills?.size ?: 0
    Column(Modifier.background(Cf.colors.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Cf.type.h2, color = Cf.colors.foreground)
            Spacer(Modifier.width(10.dp))
            FreshnessPill()
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(Lucide.Power, "Kill switches", nav::killSwitches, tint = if (activeKills > 0) Cf.colors.destructive else Cf.colors.foreground)
            }
            IconButton(Lucide.Sliders, "Settings", nav::settings)
        }
        Separator()
    }
}

@Composable
fun DetailTopBar(title: String, nav: Nav, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Column(Modifier.background(Cf.colors.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Lucide.ArrowLeft, "Back", { nav.back() })
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Cf.type.title.copy(fontSize = 16.sp), color = Cf.colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = Cf.type.caption, color = Cf.colors.mutedForeground, maxLines = 1)
            }
            actions()
        }
        Separator()
    }
}
