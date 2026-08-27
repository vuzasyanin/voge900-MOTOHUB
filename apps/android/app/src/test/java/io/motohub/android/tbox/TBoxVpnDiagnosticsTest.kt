package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxVpnDiagnosticsTest {
    @Test
    fun detectsAndroidPermissionErrorsThroughNestedCauses() {
        val error = IllegalStateException(
            "socket setup failed",
            SecurityException("connect failed: Operation not permitted")
        )

        assertTrue(TBoxVpnDiagnostics.isVpnBindBlocked(error))
    }

    @Test
    fun doesNotClassifyOrdinaryTimeoutAsVpnFailureWithoutActiveVpn() {
        val error = java.net.SocketTimeoutException("connection timed out")

        assertFalse(TBoxVpnDiagnostics.isVpnBindBlocked(error))
        assertNull(TBoxVpnDiagnostics.userFacingMessage(error, routing = null))
    }

    @Test
    fun namesLockdownWhenBindIsRefusedWithoutRouteCapture() {
        val error = IllegalStateException("Binding socket to network failed: EPERM")
        val routing = TBoxVpnDiagnostics.VpnRouting(
            interfaceName = "tun0",
            capturesDefaultRoute = false,
            capturesDash = false
        )

        assertEquals(
            TBoxVpnDiagnostics.lockdownMessage(routing),
            TBoxVpnDiagnostics.userFacingMessage(error, routing)
        )
        assertTrue(TBoxVpnDiagnostics.isVpnRoutingMessage(TBoxVpnDiagnostics.lockdownMessage(routing)))
    }

    @Test
    fun namesFullTunnelWhenTheDashRouteIsCaptured() {
        val routing = TBoxVpnDiagnostics.VpnRouting(
            interfaceName = "tun0",
            capturesDefaultRoute = true,
            capturesDash = false
        )

        assertEquals(
            TBoxVpnDiagnostics.blockingMessage(routing),
            TBoxVpnDiagnostics.userFacingMessage(
                error = IllegalStateException("network request timed out"),
                routing = routing
            )
        )
    }

    @Test
    fun aBystanderVpnIsNotBlamedForAnOrdinaryTimeout() {
        val routing = TBoxVpnDiagnostics.VpnRouting(
            interfaceName = "tun0",
            capturesDefaultRoute = false,
            capturesDash = false
        )

        assertNull(
            TBoxVpnDiagnostics.userFacingMessage(
                error = java.net.SocketTimeoutException("connection timed out"),
                routing = routing
            )
        )
    }
}
