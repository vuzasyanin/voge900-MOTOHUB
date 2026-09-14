package io.motohub.android.tbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.nsd.NsdManager
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The transport-independent view of an established link to a T-Box, so discovery, the EasyConn
 * command socket, and diagnostics don't have to know whether they are riding on a normal Wi-Fi
 * AP or a Wi-Fi Direct group.
 *
 * Two shapes exist because CFMoto dashes come in two connection styles:
 *  - [Infrastructure]: a classic WPA2 access point joined via `WifiNetworkSpecifier`. There is a
 *    real [Network] to bind sockets and NSD to.
 *  - [WifiDirect]: the dash runs as a Wi-Fi Direct Group Owner (SSID `DIRECT-...`, e.g. CL-C450).
 *    A P2P group has no `ConnectivityManager.Network`, so sockets are bound to the phone's P2P
 *    source IP instead and the group owner (`192.168.49.1`) is known up front.
 */
sealed interface TBoxLink {
    /** The bound network for the infrastructure path; null on a Wi-Fi Direct group. */
    val network: Network?

    /** The known/derived T-Box peer address, when one is available without discovery. */
    val peerHint: Inet4Address?

    /** A short human tag for logs. */
    val label: String

    /** Creates an unconnected socket that will egress over this link. */
    fun createSocket(): Socket

    /** Releases link-specific resources. The AP request itself remains owned by TBoxNetworkConnector. */
    fun disconnect()

    /**
     * Drops local watchers without tearing the radio down. Recovery reuses a still-formed
     * Wi-Fi Direct group; [disconnect] would `removeGroup` and the next join would be refused
     * from the background. Infrastructure and hotspot links have nothing to keep, so this is
     * the same as [disconnect].
     */
    fun detachForRecovery() = disconnect()

    /**
     * Discovery failed after the radio was up. A P2P group must stay so the next attempt can
     * adopt it instead of tearing it down and paying for Xiaomi's refused first join; an
     * access-point link has nothing extra to keep.
     */
    fun releaseAfterFailedDiscovery() {
        if (this is WifiDirect) detachForRecovery() else disconnect()
    }

    /** Starts NSD discovery over this link, hiding the network-bound vs. default-network overload. */
    fun startNsdDiscovery(
        nsdManager: NsdManager,
        serviceType: String,
        executor: Executor,
        listener: NsdManager.DiscoveryListener
    )

    /** Whether a resolved NSD service on [resolvedNetwork] belongs to this link. */
    fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean

    /**
     * The transport this link turned out to be, so a completed connection can be recorded as the
     * answer for next time. THINKERRIDE is deliberately not among the results: it rides the
     * infrastructure path here and differs only in which side opens the TCP connection, which a
     * link cannot report.
     */
    val transport: TBoxConnectionMode
        get() = when (this) {
            is WifiDirect -> TBoxConnectionMode.WIFI_DIRECT
            is PhoneHotspot -> TBoxConnectionMode.PHONE_HOTSPOT
            is Infrastructure -> TBoxConnectionMode.ACCESS_POINT
        }

    class Infrastructure(override val network: Network) : TBoxLink {
        override val peerHint: Inet4Address? = null
        override val label: String get() = "network=$network"

        override fun createSocket(): Socket = network.socketFactory.createSocket()

        override fun disconnect() = Unit

        override fun startNsdDiscovery(
            nsdManager: NsdManager,
            serviceType: String,
            executor: Executor,
            listener: NsdManager.DiscoveryListener
        ) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
        }

        override fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean =
            resolvedNetwork == network
    }

    /**
     * The phone hosts the network and the dash joined it - see
     * [io.motohub.android.session.TBoxConnectionMode.PHONE_HOTSPOT].
     *
     * Like [WifiDirect] there is no `ConnectivityManager` network to bind to, so sockets are bound
     * to the phone's own address on the tethering interface. Unlike it there is no known peer: the
     * dash is a DHCP client somewhere on [subnet], so [peerHint] is null on purpose and discovery
     * has to sweep for it (`TBoxHotspotScan`).
     */
    class PhoneHotspot(val subnet: TBoxHotspotScan.Subnet) : TBoxLink {
        override val network: Network? = null
        override val peerHint: Inet4Address? = null
        // "hotspot" was a claim this link is in no position to make: the scan behind it accepts
        // any private /24 the phone holds, so a log line reading "hotspot wlan0" was produced on
        // a phone whose hotspot was switched off and whose wlan0 was the rider's home Wi-Fi. The
        // interface name is the fact; what is hosting it is not.
        override val label: String
            get() = "phone-hosted ${subnet.interfaceName} ${subnet.localAddress.hostAddress}/${subnet.prefixLength}"

        override fun createSocket(): Socket = Socket().apply { bind(InetSocketAddress(subnet.localAddress, 0)) }

        override fun disconnect() = Unit

        override fun startNsdDiscovery(
            nsdManager: NsdManager,
            serviceType: String,
            executor: Executor,
            listener: NsdManager.DiscoveryListener
        ) {
            // No bound Network to scope this to; the unscoped overload at least covers the case
            // where the platform routes mDNS over the tethering interface.
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        // Nothing resolved over the default network belongs to a link with no Network of its own.
        override fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean = resolvedNetwork == null
    }

    /**
     * @param formedElsewhere the group was formed by ANOTHER process (the companion app) and
     *   handed over. Nothing here may release it - the process that formed it owns it and is the
     *   only one that can put it back - and a recovery must reuse this link rather than rejoining,
     *   because this process cannot resolve the group's local address on its own.
     */
    class WifiDirect(
        val bindIp: Inet4Address,
        val gatewayIp: Inet4Address,
        private val leaveGroup: () -> Unit,
        private val appContext: Context? = null,
        val formedElsewhere: Boolean = false
    ) : TBoxLink {
        override val network: Network? = null
        override val peerHint: Inet4Address = gatewayIp
        override val label: String get() = "p2p ${bindIp.hostAddress}->${gatewayIp.hostAddress}"
        private val groupWatchers = CopyOnWriteArrayList<AutoCloseable>()

        /**
         * Notifies [onLost] once when the P2P group dissolves (dash off, out of range, P2P
         * disabled). A P2P group has no ConnectivityManager network, so without this the only
         * loss signal was the video watchdog's 10s frame stall. Returns a handle to stop
         * watching; [disconnect] also closes any watcher still open.
         */
        fun watchGroupLost(onLost: () -> Unit): AutoCloseable {
            val context = appContext
                ?: return AutoCloseable { }
            val fired = AtomicBoolean(false)
            fun fire() {
                if (fired.compareAndSet(false, true)) onLost()
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            val info = intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                                WifiP2pInfo::class.java
                            )
                            if (info != null && !info.groupFormed) fire()
                        }
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            if (!enabled) fire()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            val closer = object : AutoCloseable {
                private val closed = AtomicBoolean(false)
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    runCatching { context.unregisterReceiver(receiver) }
                    groupWatchers.remove(this)
                }
            }
            groupWatchers += closer
            return closer
        }

        override fun createSocket(): Socket {
            // The group's 192.168.49.0/24 subnet is on-link on the p2p interface, so even an
            // unbound socket egresses there by destination route. Pin the source address only
            // when it is still assigned: the p2p address can be reassigned by DHCP between the
            // join and later socket use, and binding a stale address throws EADDRNOTAVAIL. If
            // pinning is not possible, an unbound socket still reaches 192.168.49.1.
            // For a group formed elsewhere the address is real but invisible to this process's
            // NetworkInterface view - that invisibility is the whole reason the addresses are
            // handed over - so the captured one is still worth trying: the kernel binds by what
            // is assigned to the device, not by what this API is willing to list. If that is
            // refused too, the unbound socket below is the documented fallback.
            val source = currentP2pSourceIp() ?: bindIp.takeIf { formedElsewhere }
            if (source != null) {
                val pinned = Socket()
                try {
                    pinned.bind(InetSocketAddress(source, 0))
                    return pinned
                } catch (failure: Exception) {
                    runCatching { pinned.close() }
                    if (unpinnedSocketReported.compareAndSet(false, true)) {
                        ProjectionEventLog.warning(
                            "WIFI_DIRECT",
                            "Cannot pin sockets to ${source.hostAddress} " +
                                "(${failure.javaClass.simpleName}: ${failure.message}); using " +
                                "unbound sockets, which reach the dash only while the p2p route " +
                                "wins over the default network."
                        )
                    }
                }
            }
            return Socket()
        }

        /** One line per link, not per socket: the reverse channels alone would repeat it. */
        private val unpinnedSocketReported = AtomicBoolean(false)

        override fun disconnect() {
            groupWatchers.toList().forEach { runCatching { it.close() } }
            leaveGroup()
        }

        override fun detachForRecovery() {
            groupWatchers.toList().forEach { runCatching { it.close() } }
        }

        /** The p2p source address assigned right now — the captured one if still present, else any. */
        private fun currentP2pSourceIp(): Inet4Address? = runCatching {
            val captured = bindIp.hostAddress
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.hostAddress == captured }
                ?.let { return it }
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("p2p") }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { addr ->
                    val host = addr.hostAddress
                    host != null && host.startsWith("192.168.49.") && host != "192.168.49.1"
                }
        }.getOrNull()

        override fun startNsdDiscovery(
            nsdManager: NsdManager,
            serviceType: String,
            executor: Executor,
            listener: NsdManager.DiscoveryListener
        ) {
            // A P2P group exposes no bindable Network, so fall back to default-network discovery.
            // Best-effort only: on many devices the default network stays cellular over P2P, in
            // which case discovery yields nothing and the caller relies on [peerHint] + wake probe.
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        override fun matchesResolvedNetwork(resolvedNetwork: Network?): Boolean = true
    }
}
