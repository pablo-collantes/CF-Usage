package io.github.pablocollantes.cfusage.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * How often the server sends this phone a new summary (`/api/v1/events?every=`). Alerts and kill
 * switches don't wait for it: the server sends those at once. [meteredMin] 0 means "same as on
 * Wi-Fi". Pure, so it is unit tested.
 */
data class UpdateRate(val everyMin: Int = 1, val meteredMin: Int = 0) {
    fun effectiveMin(metered: Boolean) = if (metered && meteredMin > 0) meteredMin else everyMin
    fun effectiveSeconds(metered: Boolean) = effectiveMin(metered) * 60
}

object UpdateRates {
    val choices = listOf(1, 2, 5, 15, 30)

    /** 0 is "same as on Wi-Fi". */
    val meteredChoices = listOf(0, 2, 5, 15, 30)

    /**
     * About how much the event stream uses per month at each rate, in MB, with the phone on that
     * network all month. Measured on the live stream on 2026-10-07 (an update is 300-415 bytes
     * gzipped), plus about 250 bytes of TLS, TCP and WireGuard per event, a 70 s keep-alive
     * whenever nothing else is sent, and a reconnect every 2 h. Keep-alives and reconnects are
     * about 13 MB of every figure. Edit here only.
     */
    val monthlyMb = mapOf(1 to 30, 2 to 23, 5 to 18, 15 to 15, 30 to 14)

    fun monthly(min: Int): String = monthlyMb[min]?.let { "about $it MB a month" } ?: ""

    /** "every minute", "every 15 min". */
    fun every(min: Int): String = if (min == 1) "every minute" else "every $min min"

    /** Stored values outside the choices fall back to the default rather than reaching the server. */
    fun sanitize(r: UpdateRate) = UpdateRate(
        everyMin = r.everyMin.takeIf { it in choices } ?: 1,
        meteredMin = r.meteredMin.takeIf { it in meteredChoices } ?: 0,
    )
}

/** The update rate, kept in SharedPreferences so the service and widgets can read it synchronously. */
class UpdateRateStore(context: Context) {
    private val sp = context.getSharedPreferences("update_rate", Context.MODE_PRIVATE)
    private val _rate = MutableStateFlow(UpdateRates.sanitize(UpdateRate(sp.getInt(EVERY, 1), sp.getInt(METERED, 0))))
    val rate: StateFlow<UpdateRate> = _rate.asStateFlow()

    fun set(rate: UpdateRate) {
        val r = UpdateRates.sanitize(rate)
        sp.edit {
            putInt(EVERY, r.everyMin)
            putInt(METERED, r.meteredMin)
        }
        _rate.value = r
    }

    private companion object {
        const val EVERY = "every_min"
        const val METERED = "metered_min"
    }
}

/**
 * Whether the default network is metered (mobile data, a hotspot). Behind Tailscale the default
 * network is the VPN, which reports its underlying network's meteredness.
 */
class NetworkState(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(cm?.isActiveNetworkMetered ?: false)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _metered.value = isMetered(caps)
                }
            })
        }.onFailure { Log.w("CfUsage", "can't watch the network; treating it as unmetered", it) }
    }

    private fun isMetered(caps: NetworkCapabilities): Boolean {
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        return !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    }
}

/** The rate in effect right now, in seconds, as it changes. */
fun streamEvery(rate: StateFlow<UpdateRate>, metered: StateFlow<Boolean>): Flow<Int> =
    combine(rate, metered) { r, m -> r.effectiveSeconds(m) }.distinctUntilChanged()
