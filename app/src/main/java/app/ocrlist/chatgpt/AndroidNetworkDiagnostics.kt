package app.ocrlist.chatgpt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Fixed labels only: no SSIDs, addresses, account details, URLs, or credentials. */
class AndroidNetworkDiagnostics(context: Context, private val isResumed: () -> Boolean) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun describe(): String {
        val parts = mutableListOf(if (isResumed()) "app foreground" else "app background")
        try {
            val network = connectivity.activeNetwork
            val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            if (capabilities == null) parts += "no app-accessible network"
            else {
                parts += when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    else -> "other network"
                }
                parts += if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "network validated" else "network not validated"
            }
            parts += when (connectivity.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "Data Saver restricting background data"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "Data Saver exemption"
                else -> "Data Saver off"
            }
        } catch (_: Exception) { parts += "network state unavailable" }
        return parts.joinToString(", ")
    }
}
