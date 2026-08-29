package studio.koeda.norrklang.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device's default network is cellular, driving the
 * per-network quality tiers (see [StreamUrlResolver]). A VPN network carries
 * its underlying transports in its capabilities, so VPN-over-LTE still counts
 * as cellular.
 */
internal class NetworkMonitor(context: Context) : AutoCloseable {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val _onCellular = MutableStateFlow(currentDefaultIsCellular())
    val onCellular: StateFlow<Boolean> = _onCellular.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _onCellular.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        // onLost keeps the last known transport: with no network the tier is
        // moot, and the next default network updates it via the callback.
    }

    init {
        // Registration can throw (e.g. TooManyRequestsException); the tier
        // then stays at the construction-time snapshot — same guard as
        // PlaybackRecoveryListener.
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
    }

    override fun close() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun currentDefaultIsCellular(): Boolean {
        val manager = connectivityManager ?: return false
        val capabilities =
            runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
}
