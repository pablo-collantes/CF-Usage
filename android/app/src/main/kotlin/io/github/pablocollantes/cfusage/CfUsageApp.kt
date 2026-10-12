package io.github.pablocollantes.cfusage

import android.app.Application
import android.content.Context
import io.github.pablocollantes.cfusage.data.AppContainer
import io.github.pablocollantes.cfusage.push.Notifications
import io.github.pablocollantes.cfusage.push.SafetyNetWorker

class CfUsageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        SafetyNetWorker.schedule(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as CfUsageApp).container
