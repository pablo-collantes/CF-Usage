package io.github.pablocollantes.cfusage.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.pablocollantes.cfusage.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms the monitor after a reboot or an app update, without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // BOOT_COMPLETED is one of the few broadcasts allowed to start a foreground service.
                if (context.container.prefs.monitorEnabledNow()) MonitorService.start(context)
                SafetyNetWorker.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
