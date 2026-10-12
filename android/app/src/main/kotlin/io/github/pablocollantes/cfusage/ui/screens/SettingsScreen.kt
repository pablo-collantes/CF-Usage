package io.github.pablocollantes.cfusage.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.BuildConfig
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.MetricCatalog
import io.github.pablocollantes.cfusage.data.ServerSettings
import io.github.pablocollantes.cfusage.data.Thresholds
import io.github.pablocollantes.cfusage.data.UpdateRate
import io.github.pablocollantes.cfusage.data.UpdateRates
import io.github.pablocollantes.cfusage.push.MonitorService
import io.github.pablocollantes.cfusage.ui.DetailTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.appViewModel
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.Callout
import io.github.pablocollantes.cfusage.ui.components.CardHeader
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.LoadingBlock
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SegmentedTabs
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.components.SwitchRow
import io.github.pablocollantes.cfusage.ui.components.TextInput
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.util.Times
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val serverUrl = c.prefs.serverUrl
    val monitorEnabled = c.prefs.monitorEnabled
    val updateRate = c.updateRate.rate
    val metered = c.network.metered
    private val _test = MutableStateFlow<Pair<Boolean, String>?>(null)
    val test = _test.asStateFlow()
    private val _server = MutableStateFlow(LoadState<ServerSettings>())
    val server = _server.asStateFlow()
    private val _saved = MutableStateFlow<String?>(null)
    val saved = _saved.asStateFlow()

    fun saveUrl(context: Context, url: String) {
        viewModelScope.launch {
            c.prefs.setServerUrl(url)
            _test.value = null
            MonitorService.start(context.applicationContext) // reconnect to the new address
            testConnection()
            loadServer()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _test.value = null
            _test.value = attempt { c.api.health() }.fold(
                { h ->
                    val poll = Times.parse(h.lastPollAt)?.let { "last poll ${Times.hhmm(it)}" } ?: "no poll yet"
                    (h.ok && h.pollError == null) to "Server ${h.version} · $poll" + (h.pollError?.let { " · $it" } ?: "")
                },
                { false to (it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun loadServer() {
        viewModelScope.launch {
            _server.value = _server.value.copy(loading = true, error = null)
            _server.value = attempt { c.api.settings() }.fold({ LoadState(it) }, { LoadState(_server.value.data, error = it.message) })
        }
    }

    fun saveServer(s: ServerSettings) {
        viewModelScope.launch {
            _saved.value = null
            attempt { c.api.putSettings(s) }
                .onSuccess { _server.value = LoadState(it); _saved.value = "Saved" }
                .onFailure { _saved.value = "Not saved: ${it.message}" }
        }
    }

    /** The monitor service sees the change and reconnects with the new rate. */
    fun setUpdateRate(rate: UpdateRate) = c.updateRate.set(rate)

    fun setMonitor(context: Context, on: Boolean) {
        viewModelScope.launch {
            c.prefs.setMonitorEnabled(on)
            if (on) MonitorService.start(context.applicationContext) else MonitorService.stop(context.applicationContext)
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav) {
    val vm = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val url by vm.serverUrl.collectAsState(BuildConfig.DEFAULT_SERVER_URL)
    val monitor by vm.monitorEnabled.collectAsState(true)
    val test by vm.test.collectAsState()
    val server by vm.server.collectAsState()
    val saved by vm.saved.collectAsState()
    val rate by vm.updateRate.collectAsState()
    val metered by vm.metered.collectAsState()
    var urlDraft by remember(url) { mutableStateOf(url) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    LaunchedEffect(Unit) {
        vm.testConnection()
        vm.loadServer()
    }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar("Settings", nav)
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CfCard(Modifier.fillMaxWidth()) {
                    CardHeader("Usage server", "Reached over Tailscale; tailscale serve provides HTTPS")
                    Column(Modifier.padding(16.dp)) {
                        TextInput(urlDraft, { urlDraft = it }, Modifier.fillMaxWidth(), placeholder = BuildConfig.DEFAULT_SERVER_URL, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CfButton("Save", { vm.saveUrl(context, urlDraft) }, enabled = urlDraft.startsWith("https://") && urlDraft != url, small = true)
                            CfButton("Test connection", vm::testConnection, variant = ButtonVariant.Outline, small = true, icon = Lucide.Refresh)
                        }
                        test?.let { (ok, msg) ->
                            Spacer(Modifier.height(10.dp))
                            Callout(if (ok) "Connected" else "Can't reach the server", msg, icon = if (ok) Lucide.CircleCheck else Lucide.WifiOff, destructive = !ok)
                        }
                        if (!urlDraft.startsWith("https://")) {
                            Spacer(Modifier.height(6.dp))
                            Text("The address must start with https://", style = Cf.type.caption, color = Cf.colors.destructive)
                        }
                    }
                }
            }
            item {
                val notifGranted = remember(permissionTick) {
                    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }
                val exempt = remember(permissionTick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
                CfCard(Modifier.fillMaxWidth()) {
                    CardHeader("Live connection", "A foreground service keeps one event stream open for alerts and widgets")
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SwitchRow("Keep connected", "Shows a silent notification while connected", monitor, { vm.setMonitor(context, it) })
                        Separator()
                        PermissionRow("Notifications", if (notifGranted) "Allowed" else "Needed for alerts", notifGranted) {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        Separator()
                        PermissionRow(
                            "Battery optimisation",
                            if (exempt) "Exempt: the connection survives Doze" else "Samsung and others pause the connection without this",
                            exempt,
                        ) {
                            requestBatteryExemption(context)
                            permissionTick++
                        }
                    }
                }
            }
            item { UpdateRateCard(rate, metered, monitor, vm::setUpdateRate) }
            item {
                CfCard(Modifier.fillMaxWidth()) {
                    CardHeader("Alert rules", "Stored on the server, which evaluates them every poll")
                    when {
                        server.data != null -> ServerSettingsForm(server.data!!, saved, vm::saveServer)
                        server.loading -> LoadingBlock()
                        else -> Column(Modifier.padding(16.dp)) { ErrorCallout(server.error ?: "Not loaded", vm::loadServer) }
                    }
                }
            }
            item {
                Text(
                    "CF Usage ${BuildConfig.VERSION_NAME} · io.github.pablocollantes.cfusage",
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    style = Cf.type.caption,
                    color = Cf.colors.mutedForeground,
                )
            }
        }
    }
}

// Sideloaded app: the direct exemption request is fine here (Play's policy on it doesn't apply).
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/** How often new numbers reach this phone, with about what each choice costs in data. */
@Composable
private fun UpdateRateCard(rate: UpdateRate, metered: Boolean, connected: Boolean, onChange: (UpdateRate) -> Unit) {
    CfCard(Modifier.fillMaxWidth()) {
        CardHeader("Update rate", "How often the server sends this phone new numbers")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Update every (minutes)", style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
                Spacer(Modifier.height(6.dp))
                SegmentedTabs(UpdateRates.choices.map { it to "$it" }, rate.everyMin, { onChange(rate.copy(everyMin = it)) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(UpdateRates.monthly(rate.everyMin).replaceFirstChar { it.uppercase() }, style = Cf.type.caption, color = Cf.colors.mutedForeground)
            }
            Column {
                Text("On mobile data (minutes)", style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
                Text("Mobile data and other metered networks, such as a hotspot", style = Cf.type.caption, color = Cf.colors.mutedForeground)
                Spacer(Modifier.height(6.dp))
                SegmentedTabs(
                    UpdateRates.meteredChoices.map { it to if (it == 0) "Same" else "$it" },
                    rate.meteredMin,
                    { onChange(rate.copy(meteredMin = it)) },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    UpdateRates.monthly(rate.effectiveMin(true)).replaceFirstChar { it.uppercase() } + " on mobile data",
                    style = Cf.type.caption,
                    color = Cf.colors.mutedForeground,
                )
            }
            val now = rate.effectiveMin(metered)
            Text(
                if (connected) "Now ${UpdateRates.every(now)}, on ${if (metered) "mobile data" else "Wi-Fi"}" else "Keep connected is off, so nothing is pushed",
                style = Cf.type.small.copy(fontWeight = FontWeight.Medium),
                color = Cf.colors.foreground,
            )
            Text(
                "Alerts and kill switches still arrive instantly, and the server still checks Cloudflare every minute. " +
                    "Widgets update at this rate. Opening the app always fetches fresh numbers. " +
                    "Data figures assume the phone stays on that network all month.",
                style = Cf.type.caption,
                color = Cf.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, body: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Cf.type.body.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
            Text(body, style = Cf.type.small, color = Cf.colors.mutedForeground)
        }
        Spacer(Modifier.width(8.dp))
        if (ok) {
            androidx.compose.material3.Icon(Lucide.CircleCheck, "OK", Modifier.size(20.dp), tint = Cf.colors.chart2)
        } else {
            CfButton("Allow", onFix, variant = ButtonVariant.Outline, small = true)
        }
    }
}

@Composable
private fun CheckRow(title: String, sub: String?, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Cf.colors.primary, checkmarkColor = Cf.colors.primaryForeground, uncheckedColor = Cf.colors.border),
        )
        Text(title, Modifier.weight(1f), style = Cf.type.small, color = Cf.colors.foreground)
        if (sub != null) Text(sub, Modifier.padding(end = 8.dp), style = Cf.type.caption, color = Cf.colors.mutedForeground)
    }
}

private fun pctText(v: Double) = String.format(Locale.US, "%.0f", v * 100)

@Composable
private fun ServerSettingsForm(s: ServerSettings, saved: String?, onSave: (ServerSettings) -> Unit) {
    var cycleDay by remember(s) { mutableStateOf(s.cycleDay.toString()) }
    var info by remember(s) { mutableStateOf(pctText(s.thresholds.info)) }
    var warn by remember(s) { mutableStateOf(pctText(s.thresholds.warn)) }
    var critical by remember(s) { mutableStateOf(pctText(s.thresholds.critical)) }
    var projection by remember(s) { mutableStateOf(s.projectionAlert) }
    var spikeFactor by remember(s) { mutableStateOf(s.spikeFactor.toString()) }
    var spikeShare by remember(s) { mutableStateOf(String.format(Locale.US, "%.1f", s.spikeMinShare * 100)) }
    var muted by remember(s) { mutableStateOf(s.muted.toSet()) }

    val day = cycleDay.toIntOrNull()
    val i = info.toDoubleOrNull()
    val w = warn.toDoubleOrNull()
    val c = critical.toDoubleOrNull()
    val sf = spikeFactor.toDoubleOrNull()
    val ss = spikeShare.toDoubleOrNull()
    val valid = day != null && day in 1..28 && i != null && w != null && c != null && i < w && w < c && c <= 100 && i > 0 &&
        sf != null && sf >= 1 && ss != null && ss >= 0

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField("Billing cycle starts on day (UTC)", cycleDay, { cycleDay = it }, "1–28")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField("Notice %", info, { info = it }, "50", Modifier.weight(1f))
            LabeledField("Warning %", warn, { warn = it }, "75", Modifier.weight(1f))
            LabeledField("Critical %", critical, { critical = it }, "90", Modifier.weight(1f))
        }
        SwitchRow("Projection alerts", "Warn when the end-of-cycle estimate passes 100%", projection, { projection = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField("Spike factor ×", spikeFactor, { spikeFactor = it }, "5", Modifier.weight(1f))
            LabeledField("Spike min. % of allowance", spikeShare, { spikeShare = it }, "1", Modifier.weight(1f))
        }
        Text("Muted metrics (never alert)", style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
        Column {
            MetricCatalog.all.forEach { e ->
                CheckRow(e.label, null, e.key in muted) { muted = if (e.key in muted) muted - e.key else muted + e.key }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CfButton(
                "Save rules",
                {
                    onSave(
                        ServerSettings(
                            cycleDay = day!!,
                            thresholds = Thresholds(i!! / 100, w!! / 100, c!! / 100),
                            projectionAlert = projection,
                            spikeFactor = sf!!,
                            spikeMinShare = ss!! / 100,
                            muted = MetricCatalog.all.map { it.key }.filter { it in muted },
                        ),
                    )
                },
                enabled = valid,
            )
            Spacer(Modifier.width(12.dp))
            if (!valid) Text("Check the values: notice < warning < critical ≤ 100", style = Cf.type.caption, color = Cf.colors.destructive)
            else if (saved != null) Text(saved, style = Cf.type.caption, color = Cf.colors.mutedForeground)
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        TextInput(value, onChange, Modifier.fillMaxWidth(), placeholder = placeholder, keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
}
