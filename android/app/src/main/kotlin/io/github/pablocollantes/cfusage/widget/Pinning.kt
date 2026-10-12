package io.github.pablocollantes.cfusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Add to home screen": asks the launcher to pin a widget of the design's size. The launcher
 * reports the new widget's id through [PinResultReceiver], which assigns the design to it.
 */
object Pinning {
    const val EXTRA_DESIGN = "design"

    fun supported(context: Context) = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    fun pin(context: Context, design: WidgetDesign): Boolean {
        val kind = ProviderKind.forPreset(design.size)
        val provider = ComponentName(context, WidgetPlacements.receiverFor(kind))
        val callback = Intent(context, PinResultReceiver::class.java).putExtra(EXTRA_DESIGN, design.id)
        // Mutable so the launcher can add EXTRA_APPWIDGET_ID; the intent is explicit, so nothing else can claim it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, design.id.hashCode(), callback, flags)
        return AppWidgetManager.getInstance(context).requestPinAppWidget(provider, null, pi)
    }
}

class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val design = intent.getStringExtra(Pinning.EXTRA_DESIGN) ?: return
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetPlacements.assign(app, widgetId, design)
            } finally {
                pending.finish()
            }
        }
    }
}
