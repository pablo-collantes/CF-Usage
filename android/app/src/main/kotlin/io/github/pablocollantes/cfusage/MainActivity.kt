package io.github.pablocollantes.cfusage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.pablocollantes.cfusage.data.KillTarget
import io.github.pablocollantes.cfusage.push.MonitorService
import io.github.pablocollantes.cfusage.ui.AppRoot
import io.github.pablocollantes.cfusage.ui.DeepLink
import io.github.pablocollantes.cfusage.ui.theme.CfUsageTheme
import io.github.pablocollantes.cfusage.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** FragmentActivity (not plain ComponentActivity) because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val deepLinks = MutableStateFlow<DeepLink?>(null)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinks.value = parse(intent)
        maybeAskForNotifications()
        lifecycleScope.launch {
            // Opening the app is a foreground start, which Android always allows.
            if (container.prefs.monitorEnabledNow()) MonitorService.start(this@MainActivity)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { keepFresh() } }
        setContent {
            CfUsageTheme {
                AppRoot(deepLinks) { deepLinks.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinks.value = parse(intent)
    }

    /**
     * While the app is on screen its numbers stay about a minute old, whatever the update rate:
     * when the last summary is older than that, fetch one directly. The first check waits a
     * moment because a cold start reconnects the stream, which sends a summary at once.
     */
    private suspend fun keepFresh() {
        delay(2_500)
        while (true) {
            val c = container
            if (System.currentTimeMillis() - c.summaryStore.receivedAt.value > 75_000) {
                runCatching { c.api.summary() }.onSuccess {
                    c.summaryStore.update(it)
                    WidgetUpdater.request(this)
                }
            }
            delay(60_000)
        }
    }

    /** Alerts are the point of the app, so ask once on first launch; Settings can ask again later. */
    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun parse(intent: Intent?): DeepLink? {
        intent ?: return null
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return null
        return DeepLink(route, readKill(intent), System.nanoTime(), intent.getStringExtra(EXTRA_ARG))
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        /** The route's argument: a metric key for "metric", a design id for "widget". */
        const val EXTRA_ARG = "route_arg"
        const val ROUTE_METRIC = "metric"
        const val ROUTE_WIDGET = "widget"
        const val ROUTE_ALERTS = "alerts"
        const val ROUTE_KILL = "kill"
        private const val K_KIND = "kill_kind"
        private const val K_TARGET = "kill_target"
        private const val K_LABEL = "kill_label"
        private const val K_DESC = "kill_desc"

        fun putKill(intent: Intent, t: KillTarget) {
            intent.putExtra(K_KIND, t.kind).putExtra(K_TARGET, t.target).putExtra(K_LABEL, t.label).putExtra(K_DESC, t.description)
        }

        private fun readKill(intent: Intent): KillTarget? {
            val kind = intent.getStringExtra(K_KIND) ?: return null
            val target = intent.getStringExtra(K_TARGET) ?: return null
            return KillTarget(kind, target, intent.getStringExtra(K_LABEL).orEmpty(), intent.getStringExtra(K_DESC).orEmpty())
        }
    }
}
