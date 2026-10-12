package io.github.pablocollantes.cfusage.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.pablocollantes.cfusage.container

/**
 * The three home-screen providers. They differ only in default size and fallback design: every
 * one renders whatever design is assigned to it (per widget, in Glance state). The class names
 * predate designs and stay, so widgets already on a home screen keep working.
 */
abstract class DesignWidget(val kind: ProviderKind) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        provideContent {
            val prefs = currentState<Preferences>()
            val designs by c.designStore.designs.collectAsState()
            val summary by c.summaryStore.summary.collectAsState()
            DesignContent(Designs.resolve(designs, prefs[DESIGN_KEY], kind, prefs[METRIC_KEY]), summary, interactive = true)
        }
    }

    companion object {
        val DESIGN_KEY = stringPreferencesKey("design")

        /** Set by the old Metric widget's picker; still honoured until a design is assigned. */
        val METRIC_KEY = stringPreferencesKey("metric")
    }
}

/** Wide, 4×2 by default. */
class OverviewWidget : DesignWidget(ProviderKind.Wide)

/** Small, 2×2 by default. */
class MetricWidget : DesignWidget(ProviderKind.Small)

/** Large, 4×3 by default. */
class TopWorkersWidget : DesignWidget(ProviderKind.Large)

class OverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OverviewWidget()
}

class MetricWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetricWidget()
}

class TopWorkersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TopWorkersWidget()
}

/** A widget on the home screen and the design it shows. */
data class Placement(val appWidgetId: Int, val kind: ProviderKind, val designId: String, val sizes: List<DpSize>)

/** Assigning designs to placed widgets, and finding where each design is placed. */
object WidgetPlacements {
    fun widgetFor(kind: ProviderKind): DesignWidget = when (kind) {
        ProviderKind.Wide -> OverviewWidget()
        ProviderKind.Small -> MetricWidget()
        ProviderKind.Large -> TopWorkersWidget()
    }

    fun receiverFor(kind: ProviderKind): Class<out GlanceAppWidgetReceiver> = when (kind) {
        ProviderKind.Wide -> OverviewWidgetReceiver::class.java
        ProviderKind.Small -> MetricWidgetReceiver::class.java
        ProviderKind.Large -> TopWorkersWidgetReceiver::class.java
    }

    /** Which provider an appWidgetId belongs to, or null if it isn't ours. */
    fun kindOf(context: Context, appWidgetId: Int): ProviderKind? {
        val cls = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className ?: return null
        return ProviderKind.entries.firstOrNull { receiverFor(it).name == cls }
    }

    suspend fun assign(context: Context, appWidgetId: Int, designId: String) {
        val kind = kindOf(context, appWidgetId) ?: return
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        updateAppWidgetState(context, glanceId) {
            it[DesignWidget.DESIGN_KEY] = designId
            it.remove(DesignWidget.METRIC_KEY)
        }
        widgetFor(kind).update(context, glanceId)
    }

    /** The design id a placed widget shows right now (after fallbacks). */
    suspend fun designOf(context: Context, appWidgetId: Int): String? {
        val kind = kindOf(context, appWidgetId) ?: return null
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        return Designs.resolve(context.container.designStore.designs.value, state[DesignWidget.DESIGN_KEY], kind, state[DesignWidget.METRIC_KEY]).id
    }

    suspend fun all(context: Context): List<Placement> {
        val gm = GlanceAppWidgetManager(context)
        val designs = context.container.designStore.designs.value
        return ProviderKind.entries.flatMap { kind ->
            val ids = runCatching { gm.getGlanceIds(widgetFor(kind).javaClass) }.getOrDefault(emptyList())
            ids.map { glanceId ->
                val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
                Placement(
                    appWidgetId = gm.getAppWidgetId(glanceId),
                    kind = kind,
                    designId = Designs.resolve(designs, state[DesignWidget.DESIGN_KEY], kind, state[DesignWidget.METRIC_KEY]).id,
                    sizes = runCatching { gm.getAppWidgetSizes(glanceId) }.getOrDefault(emptyList()),
                )
            }
        }
    }
}
