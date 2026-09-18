package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxWifiDirectConnectorTest {
    @Test
    fun `matches the profile group name ignoring case and quotes`() {
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-CL-C450-1234", null, "DIRECT-CL-C450-1234")
        )
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("direct-cl-c450-1234", null, "\"DIRECT-CL-C450-1234\"")
        )
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile(" DIRECT-AB12 ", null, "DIRECT-AB12")
        )
    }

    @Test
    fun `rejects a formed group that belongs to another device`() {
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-tv-LivingRoom", null, "DIRECT-CL-C450-1234")
        )
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-XY99-otherbike", null, "DIRECT-CL-C450-1234")
        )
    }

    @Test
    fun `accepts an unverifiable group name rather than breaking working joins`() {
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile(null, null, "DIRECT-CL-C450-1234"))
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("", null, "DIRECT-CL-C450-1234"))
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("  ", null, "DIRECT-CL-C450-1234"))
    }

    /**
     * Field log 94b0a3da: the rider's dash raises a group called `DIRECT-iY` and his profile is
     * saved under the dash's P2P device name. Those two strings can never be equal, so the old
     * name-only check removed a link he had established by hand.
     */
    @Test
    fun `accepts the dash group when the profile holds its device name`() {
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", "VOGE-5G-9fab", "VOGE-5G-9fab")
        )
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", null, "VOGE-5G-9fab"))
        assertTrue(TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", " ", "VOGE-5G-9fab"))
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-xy-VOGE-5G-9fab", null, "VOGE-5G-9fab")
        )
    }

    @Test
    fun `rejects a group whose owner is provably another device`() {
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-iY", "LivingRoom TV", "VOGE-5G-9fab")
        )
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile("DIRECT-tv-LivingRoom", "LivingRoom", "VOGE-5G-9fab")
        )
    }

    @Test
    fun `the group owner identifies the dash for a group-name profile too`() {
        assertTrue(
            TBoxWifiDirectConnector.groupBelongsToProfile(
                "DIRECT-zz-renamed",
                "CFMOTO-EF7198",
                "DIRECT-go-CFMOTO-EF7198"
            )
        )
        assertFalse(
            TBoxWifiDirectConnector.groupBelongsToProfile(
                "DIRECT-tv-LivingRoom",
                "LivingRoom",
                "DIRECT-go-CFMOTO-EF7198"
            )
        )
    }

    @Test
    fun `recovers the dash peer name from the group ssid`() {
        assertEquals(
            "CFMOTO-EF7198",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-go-CFMOTO-EF7198")
        )
        assertEquals(
            "CL-C450-1234",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("\"DIRECT-XY-CL-C450-1234\" ")
        )
        assertEquals(
            "LivingRoom",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("direct-tv-LivingRoom")
        )
    }

    @Test
    fun `falls back to a credential join when the ssid is not a DIRECT group name`() {
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("MotoHubAP"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-AB12"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT--"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-go-"))
    }

    @Test
    fun `looks for the peer named inside a group ssid`() {
        assertEquals(
            "CFMOTO-EF7198",
            TBoxWifiDirectConnector.expectedPeerName("DIRECT-go-CFMOTO-EF7198")
        )
    }

    @Test
    fun `treats a non-group ssid as the peer name itself`() {
        assertEquals("VOGE-5G-4474", TBoxWifiDirectConnector.expectedPeerName("VOGE-5G-4474"))
        assertEquals("VOGE-5G-4474", TBoxWifiDirectConnector.expectedPeerName(" \"VOGE-5G-4474\" "))
        assertEquals("DIRECT-ee", TBoxWifiDirectConnector.expectedPeerName("DIRECT-ee"))
    }

    @Test
    fun `AUTO prefers P2P for VOGE device names and SSDQ model ids`() {
        assertTrue(
            TBoxWifiDirectConnector.prefersWifiDirectInAuto(
                MotorcycleProfile(ssid = "VOGE-5G-b780", password = "x")
            )
        )
        assertTrue(
            TBoxWifiDirectConnector.prefersWifiDirectInAuto(
                MotorcycleProfile(ssid = "voge-5g-dd7e", password = "x")
            )
        )
        assertTrue(
            TBoxWifiDirectConnector.prefersWifiDirectInAuto(
                MotorcycleProfile(ssid = "EASYCONN_5G-F3116E", password = "x", modelId = "37501")
            )
        )
        assertTrue(
            TBoxWifiDirectConnector.prefersWifiDirectInAuto(
                MotorcycleProfile(ssid = "DIRECT-go-CFMOTO-EF7198", password = "x")
            )
        )
        assertFalse(
            TBoxWifiDirectConnector.prefersWifiDirectInAuto(
                MotorcycleProfile(ssid = "EASYCONN_5G-F3116E", password = "x")
            )
        )
        assertFalse(
            TBoxWifiDirectConnector.prefersWifiDirectInAuto(
                MotorcycleProfile(ssid = "ZT5Gcf3b", password = "x")
            )
        )
    }

    @Test
    fun `retries a refused join for as long as the budget can hold another round`() {
        val budget = 35_000L
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(2_500L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(11_000L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(19_500L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(26_000L, budget))
    }

    @Test
    fun `stops retrying while there is still time to report why`() {
        val budget = 35_000L
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(26_001L, budget))
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(34_000L, budget))
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(60_000L, budget))
    }

    @Test
    fun `only a recovery attempt with no sign of the dash gets the shortened wait`() {
        val full = TBoxWifiDirectConnector.groupFormTimeoutMillis(reacquire = false, dashSeen = false)
        val shortened = TBoxWifiDirectConnector.groupFormTimeoutMillis(reacquire = true, dashSeen = false)

        assertTrue(shortened < full)
        // A rider asking for the connection always gets the full budget - the dash may be booting.
        assertEquals(
            full,
            TBoxWifiDirectConnector.groupFormTimeoutMillis(reacquire = false, dashSeen = true)
        )
        // And so does a recovery aimed at a dash that answered discovery or is already in a group.
        assertEquals(
            full,
            TBoxWifiDirectConnector.groupFormTimeoutMillis(reacquire = true, dashSeen = true)
        )
    }

    /**
     * The companion of `accepts an unverifiable group name rather than breaking working joins`:
     * such a group is still accepted as "ours", but it is no longer adopted without an address.
     * Field log 2026-09-16 burned a 1s poll on it eleven times until an Activity came to front.
     */
    @Test
    fun `a group the framework cannot describe gets a short address wait`() {
        val described = TBoxWifiDirectConnector.localAddressPollMillis("DIRECT-zu", null)
        val opaque = TBoxWifiDirectConnector.localAddressPollMillis(null, null)

        assertTrue(opaque < described)
        assertEquals(opaque, TBoxWifiDirectConnector.localAddressPollMillis("", "  "))
    }

    @Test
    fun `an opaque group without an address is not adopted`() {
        assertTrue(TBoxWifiDirectConnector.isOpaqueFormedGroup(null, null))
        assertTrue(TBoxWifiDirectConnector.isOpaqueFormedGroup("", "  "))
        assertFalse(TBoxWifiDirectConnector.isOpaqueFormedGroup("DIRECT-xA", null))
        assertFalse(
            TBoxWifiDirectConnector.shouldAdoptFormedGroup(
                groupName = null,
                ownerDeviceName = null,
                hasLocalP2pAddress = false
            )
        )
        assertTrue(
            TBoxWifiDirectConnector.shouldAdoptFormedGroup(
                groupName = null,
                ownerDeviceName = null,
                hasLocalP2pAddress = true
            )
        )
        // A named group is still adopted even before DHCP answers: checkForFormedGroup waits.
        assertTrue(
            TBoxWifiDirectConnector.shouldAdoptFormedGroup(
                groupName = "DIRECT-xA",
                ownerDeviceName = null,
                hasLocalP2pAddress = false
            )
        )
    }

    @Test
    fun `any readable group detail earns the full address wait`() {
        val full = TBoxWifiDirectConnector.localAddressPollMillis("DIRECT-zu", "VOGE-5G-b780")

        assertEquals(full, TBoxWifiDirectConnector.localAddressPollMillis("DIRECT-zu", null))
        // An owner alone is enough: a group named after the dash's P2P device never matches by
        // name, and those joins must keep the wait they have always had.
        assertEquals(full, TBoxWifiDirectConnector.localAddressPollMillis(null, "VOGE-5G-b780"))
    }

    @Test
    fun `a budget too small for one settled round refuses the very first retry`() {
        assertFalse(
            TBoxWifiDirectConnector.shouldSettleAndRetryJoin(
                elapsedMillis = 0L,
                budgetMillis = 5_000L,
                settleMillis = 6_000L,
                roundCostMillis = 3_000L
            )
        )
    }
}
