package io.github.pablocollantes.cfusage.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.pablocollantes.cfusage.MainActivity
import io.github.pablocollantes.cfusage.container
import io.github.pablocollantes.cfusage.ui.components.Badge
import io.github.pablocollantes.cfusage.ui.components.BadgeVariant
import io.github.pablocollantes.cfusage.ui.components.ButtonVariant
import io.github.pablocollantes.cfusage.ui.components.CfButton
import io.github.pablocollantes.cfusage.ui.components.CfCard
import io.github.pablocollantes.cfusage.ui.components.Lucide
import io.github.pablocollantes.cfusage.ui.components.ScreenSurface
import io.github.pablocollantes.cfusage.ui.theme.Cf
import io.github.pablocollantes.cfusage.ui.theme.CfUsageTheme
import kotlinx.coroutines.launch

/**
 * Runs when a CF Usage widget is added from the launcher, and from the widget's "Settings"
 * (reconfigure). Picks which design the widget shows, or starts a new one in the app's editor.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out must not leave a half-configured widget on the home screen.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val kind = WidgetPlacements.kindOf(this, widgetId) ?: ProviderKind.Wide
        // The widget's real size when it is already placed (reconfigure), else the provider's default.
        val opts = AppWidgetManager.getInstance(this).getAppWidgetOptions(widgetId)
        val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: kind.preset.widthDp
        val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: kind.preset.heightDp
        enableEdgeToEdge()
        setContent {
            CfUsageTheme {
                ScreenSurface {
                    val designs by container.designStore.designs.collectAsState()
                    val summary by container.summaryStore.summary.collectAsState()
                    var current by remember { mutableStateOf<String?>(null) }
                    androidx.compose.runtime.LaunchedEffect(Unit) { current = WidgetPlacements.designOf(this@WidgetConfigActivity, widgetId) }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text("Choose a widget", style = Cf.type.h2, color = Cf.colors.foreground)
                        Text(
                            "Design widgets in the app's Widgets tab. Change this one later from its Settings on the home screen.",
                            style = Cf.type.small,
                            color = Cf.colors.mutedForeground,
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                CfButton("Design a new widget", { createAndEdit(widgetId, kind) }, variant = ButtonVariant.Outline, icon = Lucide.Plus)
                            }
                            items(designs, key = { it.id }) { d ->
                                CfCard(Modifier.fillMaxWidth(), onClick = { choose(widgetId, d.id) }, borderColor = if (d.id == current) Cf.colors.foreground else Cf.colors.border) {
                                    Box(Modifier.padding(12.dp)) { WidgetStage(d, summary, w, h, maxHeight = 200.dp, pad = 12.dp) }
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.name, Modifier.weight(1f), style = Cf.type.title, color = Cf.colors.foreground, maxLines = 1)
                                        if (d.id == current) Badge("Current", BadgeVariant.Default)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, designId: String) {
        lifecycleScope.launch {
            WidgetPlacements.assign(this@WidgetConfigActivity, widgetId, designId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    /** Places the widget with a fresh copy of its size's template, then opens it in the editor. */
    private fun createAndEdit(widgetId: Int, kind: ProviderKind) {
        val base = when (kind) {
            ProviderKind.Wide -> Templates.overview(id = newId())
            ProviderKind.Small -> Templates.spotlight(id = newId())
            ProviderKind.Large -> Templates.topWorkers(id = newId())
        }
        val design = base.copy(name = "New widget", size = kind.preset)
        container.designStore.save(design)
        lifecycleScope.launch {
            WidgetPlacements.assign(this@WidgetConfigActivity, widgetId, design.id)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            startActivity(
                Intent(this@WidgetConfigActivity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_WIDGET)
                    .putExtra(MainActivity.EXTRA_ARG, design.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }
}
