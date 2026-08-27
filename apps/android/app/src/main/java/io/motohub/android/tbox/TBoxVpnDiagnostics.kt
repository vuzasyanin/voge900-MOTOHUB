package io.motohub.android.tbox

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * Says whether a VPN is actually standing between this app and the dash - and refuses to say so
 * when it is not.
 *
 * Presence of a VPN is not causation. A tunnel that does not capture the dash is a bystander;
 * a full-tunnel exit node and Android lockdown ("Block connections without VPN") are different
 * faults with different fixes.
 */
internal object TBoxVpnDiagnostics {

    data class VpnRouting(
        val interfaceName: String?,
        val capturesDefaultRoute: Boolean,
        val capturesDash: Boolean
    ) {
        val capturesTBox: Boolean get() = capturesDash || capturesDefaultRoute
        val label: String get() = interfaceName?.takeIf { it.isNotBlank() } ?: "VPN"
        fun describe(): String =
            "interface=$label, capturesDefaultRoute=$capturesDefaultRoute, capturesDashAddress=$capturesDash"
    }

    fun inspect(
        connectivityManager: ConnectivityManager,
        dashAddress: InetAddress?
    ): VpnRouting? = runCatching {
        @Suppress("DEPRECATION")
        val vpn = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return@runCatching null
        val linkProperties = connectivityManager.getLinkProperties(vpn)
        val routes = linkProperties?.routes.orEmpty()
        VpnRouting(
            interfaceName = linkProperties?.interfaceName,
            capturesDefaultRoute = routes.any { it.isDefaultRoute },
            capturesDash = dashAddress != null && routes.any { route ->
                runCatching { route.destination.contains(dashAddress) }.getOrDefault(false)
            }
        )
    }.getOrNull()

    fun isVpnBindBlocked(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val detail = listOfNotNull(current.message, current.cause?.message)
                .joinToString(" ")
                .lowercase()
            if ("eperm" in detail ||
                "operation not permitted" in detail ||
                "permission denied" in detail && "network" in detail
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun userFacingMessage(error: Throwable?, routing: VpnRouting?): String? = when {
        routing?.capturesTBox == true -> blockingMessage(routing)
        isVpnBindBlocked(error) -> lockdownMessage(routing)
        else -> null
    }

    fun blockingMessage(routing: VpnRouting?): String {
        val tunnel = routing?.label?.let { " ($it)" }.orEmpty()
        return "$VPN_ROUTING_MARKER$tunnel is routing the motorcycle's network into its tunnel, so " +
            "Android will not let MOTO-HUB reach the dash. Turn the VPN off while you ride, switch " +
            "off its exit node / full-tunnel mode, or turn on its \"allow local network access\" " +
            "option, then retry."
    }

    fun lockdownMessage(routing: VpnRouting?): String {
        val tunnel = routing?.label?.let { " ($it)" }.orEmpty()
        return "$VPN_ROUTING_MARKER$tunnel is set to block connections that do not go through " +
            "it, so Android will not let MOTO-HUB use the motorcycle's Wi-Fi at all - the dash " +
            "is only reachable there. Turn off \"Block connections without VPN\" for it in " +
            "Android's VPN settings, or turn the VPN off while you ride, then retry."
    }

    fun isVpnRoutingMessage(message: String?): Boolean =
        message?.contains(VPN_ROUTING_MARKER, ignoreCase = true) == true

    private const val VPN_ROUTING_MARKER = "A VPN on this phone"
}
