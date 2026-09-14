package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxLinkResolverTest {
    @Test
    fun `AUTO uses P2P for VOGE device names`() {
        val profile = MotorcycleProfile(
            ssid = "VOGE-5G-b780",
            password = "x",
            connectionMode = TBoxConnectionMode.AUTO
        )
        assertTrue(TBoxLinkResolver.usesWifiDirect(profile))
    }

    @Test
    fun `AUTO uses P2P when the saved model id is the SSDQ family`() {
        val profile = MotorcycleProfile(
            ssid = "EASYCONN_5G-F3116E",
            password = "x",
            modelId = "37501",
            connectionMode = TBoxConnectionMode.AUTO
        )
        assertTrue(TBoxLinkResolver.usesWifiDirect(profile))
    }

    @Test
    fun `AUTO stays on the access point for a normal SoftAP ssid`() {
        val profile = MotorcycleProfile(
            ssid = "EASYCONN_5G-F3116E",
            password = "x",
            connectionMode = TBoxConnectionMode.AUTO
        )
        assertFalse(TBoxLinkResolver.usesWifiDirect(profile))
    }

    @Test
    fun `explicit ACCESS_POINT never takes the P2P path`() {
        val profile = MotorcycleProfile(
            ssid = "VOGE-5G-b780",
            password = "x",
            connectionMode = TBoxConnectionMode.ACCESS_POINT
        )
        assertFalse(TBoxLinkResolver.usesWifiDirect(profile))
    }

    @Test
    fun `explicit WIFI_DIRECT always takes the P2P path`() {
        val profile = MotorcycleProfile(
            ssid = "ZT5Gcf3b",
            password = "x",
            connectionMode = TBoxConnectionMode.WIFI_DIRECT
        )
        assertTrue(TBoxLinkResolver.usesWifiDirect(profile))
    }

    @Test
    fun `AUTO believes the transport that actually worked over its own heuristics`() {
        // The SSID shape says access point and the dash answered over Wi-Fi Direct anyway.
        val p2pDashWithSoftApName = MotorcycleProfile(
            ssid = "EASYCONN_5G-F3116E",
            password = "x",
            connectionMode = TBoxConnectionMode.AUTO
        )
        assertTrue(
            TBoxLinkResolver.usesWifiDirect(
                p2pDashWithSoftApName,
                learned = TBoxConnectionMode.WIFI_DIRECT
            )
        )

        // And the other way round: a VOGE device name that turned out to be a real SoftAP.
        val apDashWithP2pName = MotorcycleProfile(
            ssid = "VOGE-5G-b780",
            password = "x",
            connectionMode = TBoxConnectionMode.AUTO
        )
        assertFalse(
            TBoxLinkResolver.usesWifiDirect(
                apDashWithP2pName,
                learned = TBoxConnectionMode.ACCESS_POINT
            )
        )
    }

    @Test
    fun `a learned transport never overrides a mode the rider pinned`() {
        val pinnedToAccessPoint = MotorcycleProfile(
            ssid = "VOGE-5G-b780",
            password = "x",
            connectionMode = TBoxConnectionMode.ACCESS_POINT
        )
        assertFalse(
            TBoxLinkResolver.usesWifiDirect(
                pinnedToAccessPoint,
                learned = TBoxConnectionMode.WIFI_DIRECT
            )
        )

        val pinnedToWifiDirect = MotorcycleProfile(
            ssid = "EASYCONN_5G-F3116E",
            password = "x",
            connectionMode = TBoxConnectionMode.WIFI_DIRECT
        )
        assertTrue(
            TBoxLinkResolver.usesWifiDirect(
                pinnedToWifiDirect,
                learned = TBoxConnectionMode.ACCESS_POINT
            )
        )
    }

    @Test
    fun `AUTO falls back to its heuristics when nothing has been learned yet`() {
        val vogeDeviceName = MotorcycleProfile(
            ssid = "VOGE-5G-b780",
            password = "x",
            connectionMode = TBoxConnectionMode.AUTO
        )
        assertTrue(TBoxLinkResolver.usesWifiDirect(vogeDeviceName, learned = null))
        // A transport AUTO can never take carries no answer about AUTO either.
        assertTrue(
            TBoxLinkResolver.usesWifiDirect(vogeDeviceName, learned = TBoxConnectionMode.PHONE_HOTSPOT)
        )
    }
}
