package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxCapabilitiesTest {
    @Test
    fun `maps whitelisted CLIENT_INFO fields`() {
        val result = tBoxCapabilitiesFrom(
            mapOf(
                "HUID" to "secret-huid",
                "uuid" to "secret-uuid",
                "btPin" to "1234",
                "HUName" to "CFDL26",
                "carBrand" to "CFMOTO",
                "carModel" to "reported-model",
                "pxcVersion" to "1.2.3",
                "dpi" to 160,
                "supportScreenMirroring" to true,
                "supportScreenTouch" to false,
                "supportMirrorReconnect" to true
            )
        )

        assertEquals("CFDL26", result.huName)
        assertEquals("CFMOTO", result.carBrand)
        assertEquals("reported-model", result.carModel)
        assertEquals("1.2.3", result.pxcVersion)
        assertEquals(160, result.dpi)
        assertTrue(result.screenMirroring == true)
        assertFalse(result.screenTouch == true)
        assertTrue(result.mirrorReconnect == true)
    }

    @Test
    fun `preserves missing capability flags as not reported`() {
        val result = tBoxCapabilitiesFrom(mapOf("HUName" to "T-Box"))

        assertNull(result.screenMirroring)
        assertNull(result.screenTouch)
        assertNull(result.microphone)
    }

    @Test
    fun `keeps a numeric manufacturer flavor as text`() {
        // Shipped firmware reports flavor as an int (65561 is ZONTES in the EasyConn SDK's
        // ECP_FLAVOR_APP_SDK_* table); the simulator reports a string. Both must survive.
        val result = tBoxCapabilitiesFrom(mapOf("flavor" to 65561, "channel" to 48405))

        assertEquals("65561", result.flavor)
        assertEquals("48405", result.channel)
    }

    @Test
    fun `keeps a string manufacturer flavor`() {
        val result = tBoxCapabilitiesFrom(mapOf("flavor" to "simulator"))

        assertEquals("simulator", result.flavor)
        assertNull(result.channel)
    }

    @Test
    fun `decodes flavor and channel from a raw CLIENT_INFO payload`() {
        // Guards the CLIENT_INFO_KEYS whitelist: a field absent from it is dropped before
        // tBoxCapabilitiesFrom ever sees it, so mapping alone is not enough.
        val payload = """{"HUName":"ZT-DASH","flavor":65561,"channel":"48405"}"""
            .toByteArray(Charsets.UTF_8)

        val result = decodeTBoxCapabilities(payload)

        assertEquals("65561", result?.flavor)
        assertEquals("48405", result?.channel)
    }

    @Test
    fun `reads the dash clock out of a raw CLIENT_INFO payload`() {
        // An epoch in milliseconds does not fit in an Int, which is why this field has its own
        // accessor: read as one it would come back as a truncated, meaningless number.
        val payload =
            """{"HUName":"VOGE","currentHUTime":1757836800000,"supportSyncCorrectTime":true}"""
                .toByteArray(Charsets.UTF_8)

        val result = decodeTBoxCapabilities(payload)

        assertEquals(1_757_836_800_000L, result?.currentHuTimeMillis)
        assertTrue(result?.syncCorrectTime == true)
    }

    @Test
    fun `tells a dash uptime counter apart from a wall clock`() {
        assertTrue(looksLikeDashUptime(86_400_000L))
        assertTrue(looksLikeDashUptime(0L))
        assertFalse(looksLikeDashUptime(1_757_836_800_000L))
        // "Not reported" is not a verdict either way.
        assertFalse(looksLikeDashUptime(null))
    }
}
