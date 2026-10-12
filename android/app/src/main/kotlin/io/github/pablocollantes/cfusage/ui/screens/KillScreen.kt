package io.github.pablocollantes.cfusage.ui.screens

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.data.Kill
import io.github.pablocollantes.cfusage.data.KillState
import io.github.pablocollantes.cfusage.data.KillTarget
import io.github.pablocollantes.cfusage.ui.DetailTopBar
import io.github.pablocollantes.cfusage.ui.Nav
import io.github.pablocollantes.cfusage.ui.components.Badge
import io.github.pablocollantes.cfusage.ui.components.BadgeVariant
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.Callout
import io.github.pablocollantes.cfusage.ui.components.CardHeader
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.EmptyState
import io.github.pablocollantes.cfusage.ui.components.LoadingBlock
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.SearchField
import io.github.pablocollantes.cfusage.ui.components.SectionLabel
import io.github.pablocollantes.cfusage.ui.components.Separator
import io.github.pablocollantes.cfusage.ui.components.TextInput
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.util.Times
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SheetPhase { Confirm, Submitting, Tracking, Failed }

data class KillSheet(
    val target: KillTarget,
    /** Set when the sheet restores an applied kill rather than applying a new one. */
    val restore: Kill? = null,
    val phase: SheetPhase = SheetPhase.Confirm,
    val kill: Kill? = null,
    val error: String? = null,
)

/**
 * Activity-scoped: owns the confirmation sheet (opened from any screen or a notification) and the
 * kill-switch list, and follows the server's kill events (applying → active → verified).
 */
class KillController(private val c: AppContainer) : ViewModel() {
    private val _sheet = MutableStateFlow<KillSheet?>(null)
    val sheet = _sheet.asStateFlow()
    private val _state = MutableStateFlow(LoadState<KillState>())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            c.killEvents.collect { k ->
                _sheet.value?.let { s -> if (s.kill?.id == k.id) _sheet.value = s.copy(kill = k) }
                _state.value.data?.let { st -> _state.value = _state.value.copy(data = st.copy(kills = merge(st.kills, k))) }
            }
        }
    }

    private fun merge(list: List<Kill>, k: Kill) = listOf(k) + list.filterNot { it.id == k.id }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = attempt { c.api.kills() }.fold(
                { LoadState(it) },
                { LoadState(_state.value.data, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun openApply(t: KillTarget) {
        _sheet.value = KillSheet(t)
    }

    fun openRestore(k: Kill) {
        _sheet.value = KillSheet(k.asTarget, restore = k)
    }

    fun dismiss() {
        _sheet.value = null
    }

    /** Called only after the user confirmed in the sheet and passed the unlock prompt. */
    fun submit(reason: String?) {
        val s = _sheet.value ?: return
        _sheet.value = s.copy(phase = SheetPhase.Submitting, error = null)
        viewModelScope.launch {
            attempt {
                if (s.restore != null) c.api.restoreKill(s.restore.id) else c.api.applyKill(s.target, reason?.takeIf { it.isNotBlank() })
            }.onSuccess { k ->
                c.emitKill(k)
                _sheet.value = _sheet.value?.copy(phase = SheetPhase.Tracking, kill = k)
                load()
            }.onFailure {
                _sheet.value = _sheet.value?.copy(phase = SheetPhase.Failed, error = it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun failAuth(message: String) {
        _sheet.value = _sheet.value?.copy(phase = SheetPhase.Confirm, error = message)
    }
}

/** Unlock with biometrics or the device PIN/pattern. Without any screen lock, the sheet's confirm stands alone. */
private fun authenticate(activity: FragmentActivity, title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
    // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is unsupported before Android 11; WEAK | CREDENTIAL is the equivalent there.
    val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onOk()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Confirm it's you")
            .setAllowedAuthenticators(allowed)
            .build(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KillSheetHost(controller: KillController) {
    val sheet by controller.sheet.collectAsState()
    val s = sheet ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = controller::dismiss,
        sheetState = sheetState,
        containerColor = Cf.colors.card,
        contentColor = Cf.colors.foreground,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Cf.colors.border))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            when (s.phase) {
                SheetPhase.Confirm, SheetPhase.Failed -> ConfirmContent(s, controller)
                SheetPhase.Submitting -> Row(Modifier.padding(vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Cf.colors.foreground, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Sending to the usage server…", style = Cf.type.body, color = Cf.colors.foreground)
                }
                SheetPhase.Tracking -> TrackingContent(s, controller)
            }
        }
    }
}

@Composable
private fun ConfirmContent(s: KillSheet, controller: KillController) {
    val activity = LocalActivity.current as? FragmentActivity
    var reason by remember { mutableStateOf("") }
    val restoring = s.restore != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background((if (restoring) Cf.colors.muted else Cf.colors.destructive.copy(alpha = 0.12f))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (restoring) Lucide.Undo else Lucide.Power, null, Modifier.size(20.dp), tint = if (restoring) Cf.colors.foreground else Cf.colors.destructive)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (restoring) "Restore?" else "Shut this off?", style = Cf.type.h2, color = Cf.colors.foreground)
            Text(killKindTitle(s.target.kind), style = Cf.type.small, color = Cf.colors.mutedForeground)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(s.target.label, style = Cf.type.title, color = Cf.colors.foreground)
    Spacer(Modifier.height(4.dp))
    Text(
        if (restoring) "This undoes the shut-off and puts back the setting saved when it was applied." else s.target.description,
        style = Cf.type.small,
        color = Cf.colors.mutedForeground,
    )
    if (!restoring) {
        Spacer(Modifier.height(14.dp))
        Text("Reason (optional)", style = Cf.type.caption.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
        Spacer(Modifier.height(6.dp))
        TextInput(reason, { reason = it }, Modifier.fillMaxWidth(), placeholder = "e.g. request spike on /api")
    }
    if (s.error != null) {
        Spacer(Modifier.height(14.dp))
        Callout(if (s.phase == SheetPhase.Failed) "The server refused" else "Not confirmed", s.error, icon = Lucide.TriangleAlert, destructive = true)
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CfButton("Cancel", controller::dismiss, Modifier.weight(1f), variant = ButtonVariant.Outline)
        CfButton(
            if (restoring) "Unlock and restore" else "Unlock and shut off",
            {
                val title = if (restoring) "Restore ${s.target.label}" else s.target.label
                if (activity == null) controller.submit(reason) else authenticate(activity, title, { controller.submit(reason) }, controller::failAuth)
            },
            Modifier.weight(1.4f),
            variant = if (restoring) ButtonVariant.Default else ButtonVariant.Destructive,
            icon = Lucide.Lock,
        )
    }
}

@Composable
private fun TrackingContent(s: KillSheet, controller: KillController) {
    val k = s.kill
    val restoring = s.restore != null
    Text(if (restoring) "Restoring" else "Shutting off", style = Cf.type.h2, color = Cf.colors.foreground)
    Text(s.target.label, style = Cf.type.small, color = Cf.colors.mutedForeground)
    Spacer(Modifier.height(18.dp))
    val state = k?.state ?: "applying"
    Step("Sent to the server", done = true, active = false)
    if (restoring) {
        Step("Restoring", done = state == "restored", active = state == "restoring", failed = state == "failed")
    } else {
        // Measured on 2026-10-06: a WAF block takes 6–20 s to reach every Cloudflare edge.
        Step("Applying on Cloudflare", done = state == "active", active = state == "applying", failed = state == "failed",
            note = if (state == "applying" && s.target.kind == "waf_block") "A block takes 6–20 s to reach every edge" else null)
        Step(
            "Verified",
            done = k?.verifiedAt != null,
            active = state == "active" && k?.verifiedAt == null,
            note = k?.verifiedAt?.let { t -> Times.parse(t)?.let { "at ${Times.hhmm(it)}" } },
        )
    }
    if (k?.error != null) {
        Spacer(Modifier.height(12.dp))
        Callout("Failed", k.error, icon = Lucide.TriangleAlert, destructive = true)
    }
    Spacer(Modifier.height(20.dp))
    CfButton("Done", controller::dismiss, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
}

@Composable
private fun Step(title: String, done: Boolean, active: Boolean, failed: Boolean = false, note: String? = null) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                failed -> Icon(Lucide.X, null, Modifier.size(18.dp), tint = Cf.colors.destructive)
                done -> Icon(Lucide.CircleCheck, null, Modifier.size(18.dp), tint = Cf.colors.chart2)
                active -> CircularProgressIndicator(Modifier.size(16.dp), color = Cf.colors.foreground, strokeWidth = 2.dp)
                else -> Box(Modifier.size(10.dp).clip(CircleShape).background(Cf.colors.border))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = Cf.type.body.copy(fontWeight = FontWeight.Medium), color = if (done || active) Cf.colors.foreground else Cf.colors.mutedForeground)
            if (note != null) Text(note, style = Cf.type.caption, color = Cf.colors.mutedForeground)
        }
    }
}

private fun stateBadge(state: String): Pair<String, BadgeVariant> = when (state) {
    "active" -> "Active" to BadgeVariant.Destructive
    "applying" -> "Applying" to BadgeVariant.Outline
    "restoring" -> "Restoring" to BadgeVariant.Outline
    "failed" -> "Failed" to BadgeVariant.Destructive
    "restored" -> "Restored" to BadgeVariant.Secondary
    else -> state to BadgeVariant.Outline
}

@Composable
fun KillScreen(nav: Nav) {
    val controller = nav.kill
    val state by controller.state.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { controller.load() }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("Kill switches", nav, "Emergency shut-offs, run on the server")
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Callout(
                    "How these work",
                    "The server holds the edit token; this phone only asks for a fixed action. Every shut-off saves what it changed and can be restored. Applying one needs your unlock.",
                    icon = Lucide.Shield,
                )
            }
            item { ErrorCallout(state.error, controller::load) }
            val st = state.data
            if (st == null) {
                if (state.loading) item { LoadingBlock() }
                return@LazyColumn
            }
            val current = st.kills.filter { it.state != "restored" }
            val history = st.kills.filter { it.state == "restored" }
            item { SectionLabel("On now") }
            if (current.isEmpty()) {
                item {
                    CfCard(Modifier.fillMaxWidth()) {
                        Text("Nothing is shut off.", Modifier.padding(16.dp), style = Cf.type.small, color = Cf.colors.mutedForeground)
                    }
                }
            } else {
                item {
                    CfCard(Modifier.fillMaxWidth()) {
                        current.forEachIndexed { i, k ->
                            KillRow(k) { controller.openRestore(k) }
                            if (i < current.lastIndex) Separator(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
            item { SectionLabel("Available") }
            item { SearchField(query, { query = it }, "Filter by host, script or queue", Modifier.fillMaxWidth()) }
            val activeIds = current.map { "${it.kind}:${it.target}" }.toSet()
            val available = st.available.filter { "${it.kind}:${it.target}" !in activeIds }
                .filter { query.isBlank() || query.trim().lowercase() in (it.label + " " + it.target).lowercase() }
            if (available.isEmpty()) {
                item { EmptyState("Nothing to show", "No kill switches match.", icon = Lucide.Power) }
            }
            for ((kind, targets) in available.groupBy { it.kind }) {
                item(key = "kind-$kind") {
                    CfCard(Modifier.fillMaxWidth()) {
                        CardHeader(killKindTitle(kind), kindDescription(kind))
                        targets.forEachIndexed { i, t ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.target, style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground, maxLines = 1)
                                    Text(t.label, style = Cf.type.caption, color = Cf.colors.mutedForeground, maxLines = 1)
                                }
                                Spacer(Modifier.width(8.dp))
                                CfButton("Shut off", { controller.openApply(t) }, variant = ButtonVariant.Outline, small = true, icon = Lucide.Power)
                            }
                            if (i < targets.lastIndex) Separator(Modifier.padding(horizontal = 16.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            if (history.isNotEmpty()) {
                item { SectionLabel("History") }
                item {
                    CfCard(Modifier.fillMaxWidth()) {
                        history.forEachIndexed { i, k ->
                            KillRow(k, null)
                            if (i < history.lastIndex) Separator(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun kindDescription(kind: String) = when (kind) {
    "waf_block" -> "A WAF rule blocks every request before the Worker runs, so nothing is billed."
    "workers_dev_off" -> "Turns off the script's workers.dev URL and previews."
    "cron_off" -> "Removes the cron triggers until restored."
    "queue_pause" -> "Stops delivery to consumers; messages wait in the queue."
    else -> null
}

@Composable
private fun KillRow(k: Kill, onRestore: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (text, variant) = stateBadge(k.state)
                Badge(text, variant)
                Spacer(Modifier.width(8.dp))
                Text(killKindTitle(k.kind), style = Cf.type.caption, color = Cf.colors.mutedForeground)
            }
            Spacer(Modifier.height(6.dp))
            Text(k.label, style = Cf.type.small.copy(fontWeight = FontWeight.Medium), color = Cf.colors.foreground)
            val times = buildList {
                Times.parse(k.appliedAt)?.let { add("applied ${Times.dayTime(it)}") }
                Times.parse(k.verifiedAt)?.let { add("verified ${Times.hhmm(it)}") }
                Times.parse(k.restoredAt)?.let { add("restored ${Times.dayTime(it)}") }
            }
            Text(times.joinToString(" · "), style = Cf.type.caption, color = Cf.colors.mutedForeground)
            k.reason?.let { Text("“$it”", style = Cf.type.caption, color = Cf.colors.mutedForeground) }
            k.error?.let { Text(it, style = Cf.type.caption, color = Cf.colors.destructive) }
        }
        if (onRestore != null && k.state != "restoring") {
            Spacer(Modifier.width(8.dp))
            CfButton("Restore", onRestore, variant = ButtonVariant.Outline, small = true, icon = Lucide.Undo)
        }
    }
}
