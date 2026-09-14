package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Single decision point for how to reach a T-Box. A profile can explicitly select Auto, AP, or
 * Wi-Fi Direct, which is essential for dashboards that act as P2P Group Owners without exposing
 * a conventional access point.
 */
/**
 * A Wi-Fi Direct group formed and owned by another process, described well enough for this one to
 * use it without looking it up. Only the forming process can read the phone's own address inside
 * the group, so it travels across the bridge instead of being resolved twice.
 */
data class FormedP2pGroup(
    val localIpv4: Inet4Address,
    val groupOwnerIpv4: Inet4Address
)

object TBoxLinkResolver {

    /**
     * @param formedGroup a Wi-Fi Direct group ANOTHER process already formed and still owns,
     *   with the addresses it resolved there. Present only on the companion-app bridge; it makes
     *   this process adopt that group instead of joining one of its own, which it cannot do -
     *   see [TBoxWifiDirectConnector.adoptFormedGroup].
     */
    suspend fun connect(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        formedGroup: FormedP2pGroup? = null
    ): Result<TBoxLink> =
        if (profile.connectionMode == TBoxConnectionMode.PHONE_HOTSPOT) {
            hostedLink(context).recoverCatching { hostedFailure ->
                accessPointFallback(networkConnector, profile, hostedFailure).getOrThrow()
            }
        } else if (usesWifiDirect(profile, learnedTransport(context, profile))) {
            wifiDirectLink(context, profile, formedGroup).recoverCatching { p2pFailure ->
                if (profile.connectionMode != TBoxConnectionMode.AUTO) throw p2pFailure
                accessPointFallback(networkConnector, profile, p2pFailure).getOrThrow()
            }
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through the Wi-Fi access-point transport (${profile.connectionMode})."
            )
            networkConnector.connect(profile).map { TBoxLink.Infrastructure(it) }
                .recoverCatching { apFailure ->
                    if (profile.connectionMode != TBoxConnectionMode.AUTO) throw apFailure
                    wifiDirectFallback(context, networkConnector, profile, apFailure).getOrThrow()
                }
        }

    /**
     * Recovery variant: reuse a still-alive infrastructure network before reconnecting.
     *
     * @param currentLink the link the session being recovered was using, when there is one. A
     *   Wi-Fi Direct group formed by the companion app must be re-adopted, never rejoined: this
     *   process cannot form or resolve one, so a rejoin here is the "connect() failed: internal
     *   error" storm the watchdog used to produce on every recovery of a handed-over session.
     */
    suspend fun reacquire(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        awaitNetworkMillis: Long,
        currentLink: TBoxLink? = null
    ): TBoxLink {
        if (usesWifiDirect(profile, learnedTransport(context, profile))) {
            val handedOver = (currentLink as? TBoxLink.WifiDirect)?.takeIf { it.formedElsewhere }
            if (handedOver != null) {
                return TBoxWifiDirectConnector(context)
                    .adoptFormedGroup(profile, handedOver.bindIp, handedOver.gatewayIp)
                    .getOrThrow()
            }
            // A P2P group has no ConnectivityManager-visible network to await; rejoin directly.
            return TBoxWifiDirectConnector(context).connect(profile, reacquire = true).getOrThrow()
        }
        val network = networkConnector.currentNetwork()
            ?: networkConnector.awaitNetworkAvailable(awaitNetworkMillis)
            ?: networkConnector.connect(profile).getOrThrow()
        return TBoxLink.Infrastructure(network)
    }

    /**
     * Public so a caller can find out which transport a profile will take *before* asking for it.
     * PRO needs this: it has to form the Wi-Fi Direct group in its own process before handing the
     * connect to CORE (Android only grants a P2P join to a caller with a visible activity), while
     * the access-point join still happens inside CORE. The two paths also differ in their
     * permission gate, so PRO has to know which one it is about to trigger.
     *
     * @param learned the transport that last carried a completed discovery for this dash, when
     *   one has been observed. In AUTO it outranks the heuristics below, which can only guess
     *   from an SSID shape and a provisioning id: a dash that has actually answered has already
     *   settled the question, and re-guessing it costs the rider a failed join and a fallback on
     *   every reconnect. Pure and explicit so the decision stays testable - [connect] and
     *   [reacquire] read the snapshot themselves.
     */
    fun usesWifiDirect(
        profile: MotorcycleProfile,
        learned: TBoxConnectionMode? = null
    ): Boolean = when (profile.connectionMode) {
        TBoxConnectionMode.WIFI_DIRECT -> true
        // THINKERRIDE inverts the TCP roles, but the Wi-Fi join itself is a plain access-point
        // request — the dash's AP is ordinary WPA2, so it rides the infrastructure path here.
        TBoxConnectionMode.ACCESS_POINT,
        TBoxConnectionMode.PHONE_HOTSPOT,
        TBoxConnectionMode.THINKERRIDE -> false
        TBoxConnectionMode.AUTO -> when (learned) {
            TBoxConnectionMode.WIFI_DIRECT -> true
            TBoxConnectionMode.ACCESS_POINT -> false
            // PHONE_HOTSPOT is not reachable from AUTO at all, and the rest carry no answer.
            else -> TBoxWifiDirectConnector.prefersWifiDirectInAuto(profile)
        }
    }

    private fun learnedTransport(context: Context, profile: MotorcycleProfile): TBoxConnectionMode? =
        TBoxCapabilityStore(context).load(profile)?.lastSuccessfulTransport

    private suspend fun wifiDirectLink(
        context: Context,
        profile: MotorcycleProfile,
        formedGroup: FormedP2pGroup?
    ): Result<TBoxLink> {
        ProjectionEventLog.record(
            "NETWORK",
            "Connecting to ${profile.ssid} through Wi-Fi Direct (${profile.connectionMode})" +
                if (formedGroup != null) ", adopting the group the companion app formed." else "."
        )
        return if (formedGroup != null) {
            TBoxWifiDirectConnector(context)
                .adoptFormedGroup(profile, formedGroup.localIpv4, formedGroup.groupOwnerIpv4)
                .map { it }
        } else {
            TBoxWifiDirectConnector(context).connect(profile).map { it }
        }
    }

    /**
     * AUTO tried the access point and it was not on the air. The saved SSID may be a P2P
     * device name (VOGE-5G-…, Zontes action=8) rather than a SoftAP, so one P2P join is the
     * other road — the same shape as [accessPointFallback], inverted.
     *
     * Only when the scan did *not* see the dash. A visible AP that failed to associate is a
     * password / radio problem; jumping to P2P would hide that.
     */
    private suspend fun wifiDirectFallback(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        apFailure: Throwable
    ): Result<TBoxLink> {
        if (networkConnector.isDashBroadcasting(profile) == true) return Result.failure(apFailure)
        ProjectionEventLog.record(
            "NETWORK",
            "${profile.ssid} is not broadcasting as an access point - trying Wi-Fi Direct."
        )
        return TBoxWifiDirectConnector(context).connect(profile).map { it }
    }

    /**
     * There is nothing to connect *to* in hotspot mode - the rider has already turned tethering on
     * by hand, because Android does not let an app create a hotspot with the SSID and password the
     * dash dictates. All this does is find the interface hosting it, so discovery knows which
     * subnet the dash is sitting on.
     *
     * Failing with a rider-readable message matters more here than anywhere else: "no hotspot
     * found" is something they can act on immediately, and it is by far the likeliest mistake.
     */
    private fun hostedLink(context: Context): Result<TBoxLink> {
        // Passing what the phone is *using* is not optional, though it was for a long time: the
        // parameter existed and the only production caller left it empty, so the rider's home
        // Wi-Fi (`wlan0`, a private /24 like any hotspot) was a candidate on equal footing with
        // the interface the dash was actually on. See TBoxHotspotScan.PEER_LINK_PREFIXES for the
        // log that showed it, and isHostedName() for why this can never eat a real SoftAP.
        val inUse = addressesTheNetworkStackIsUsing(context)
        val subnets = TBoxHotspotScan.tetheringSubnets(TBoxHotspotScan.snapshotInterfaces(), inUse)
        val subnet = subnets.firstOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "This motorcycle expects your phone to host the network, but no hotspot is " +
                        "running. Turn on the Android hotspot with the exact Ssid and Password " +
                        "the dash is showing, then connect again."
                )
            )
        // Naming every candidate, not counting them. A mailed-in log that says "2 candidate
        // interfaces" cannot tell us whether the right one was even on the list; one that says
        // "p2p0, wlan2" answers it in a glance.
        ProjectionEventLog.record(
            "NETWORK",
            "Phone-hosted transport: dash expected on ${subnet.localAddress.hostAddress}/" +
                "${subnet.prefixLength} via ${subnet.interfaceName}" +
                if (subnets.size > 1) {
                    " (chosen from ${subnets.joinToString { it.interfaceName }})."
                } else {
                    "."
                }
        )
        return Result.success(TBoxLink.PhoneHotspot(subnet))
    }

    /**
     * Takes the access-point road after the phone-hosted one led nowhere, but only on proof that
     * the dash is broadcasting.
     *
     * PHONE_HOTSPOT is a one-way door today, and that is the defect: HubViewModel offers the
     * mode after a single failed join, saves it, and nothing ever offers the way back. A rider
     * whose dash has a perfectly good access point - one transient timeout ago - is then told to
     * turn on a hotspot forever. Field log 2026-08-06 (OnePlus CPH2653, EASYCONN_5G-F3116E): every
     * connect from the saved profile failed instantly with "no hotspot is running" while, in the
     * same log and the same minute, an AUTO-mode profile joined the same dash's AP, resolved it
     * over NSD and reached READY.
     *
     * Deliberately narrow. It runs only when the scan actually SAW the dash: an unknown answer
     * leaves the original hotspot message standing, because "turn your hotspot on" is the right
     * advice for the rider whose dash really is a Wi-Fi client. This changes what a connect does,
     * never what the profile says - a mode the rider chose is theirs to keep, and a fallback that
     * silently rewrote it would take away the only setting that works for hotspot-only dashes.
     */
    private suspend fun accessPointFallback(
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        hostedFailure: Throwable
    ): Result<TBoxLink> {
        if (networkConnector.isDashBroadcasting(profile) != true) return Result.failure(hostedFailure)
        ProjectionEventLog.record(
            "NETWORK",
            "No hosted network, but ${profile.ssid} is broadcasting - joining its access point " +
                "instead. This motorcycle is saved as \"My phone hosts the hotspot\"; if the " +
                "access point keeps working, change the mode in manual pairing to skip this step."
        )
        return networkConnector.connect(profile).map { TBoxLink.Infrastructure(it) }
    }

    /**
     * Every IPv4 address on a network `ConnectivityManager` knows this phone is on - its Wi-Fi,
     * its mobile link, a VPN. A hosted hotspot is not among them: tethering is not surfaced to
     * apps as a [android.net.Network], which is exactly what makes this a usable "not the dash's
     * subnet" filter rather than a guess.
     *
     * Best-effort by design. If the query fails or comes back empty the scan simply runs
     * unfiltered, which is what it did before this existed.
     */
    // getAllNetworks() is deprecated with no synchronous replacement: the sanctioned API is a
    // registered NetworkCallback, which answers a question this code asks once, on demand, at the
    // start of a connect. activeNetwork alone is not enough - behind a VPN it *is* the VPN, and
    // the Wi-Fi whose subnet must not be swept stops being reported at all.
    @Suppress("DEPRECATION")
    private fun addressesTheNetworkStackIsUsing(context: Context): Set<InetAddress> =
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.allNetworks
                .mapNotNull { network -> connectivityManager.getLinkProperties(network) }
                .flatMap { properties -> properties.linkAddresses }
                .map { linkAddress -> linkAddress.address }
                .filterIsInstance<Inet4Address>()
                .toSet()
        }.getOrDefault(emptySet())
}
