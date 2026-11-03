package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpPolicyTest {

    @Test
    fun masterOffDropsEveryDatagram() {
        for (mode in listOf(UdpPolicy.NORMAL, UdpPolicy.GAME, UdpPolicy.RELAY_ONLY)) {
            for (port in listOf(53, 443, 3074, 3478, 9295, 51820)) {
                assertEquals(
                    UdpPolicy.ROUTE_DROP,
                    UdpPolicy.route(mode, false, false, port, port == 53)
                )
            }
        }
    }

    @Test
    fun gameModeSendsNonGamePortsToWarp() {
        for (port in listOf(443, 80, 8080, 12345, 50000, 3074, 53)) {
            assertEquals(
                "port $port",
                UdpPolicy.ROUTE_WARP,
                UdpPolicy.route(UdpPolicy.GAME, true, false, port, port == 53)
            )
        }
    }

    @Test
    fun normalModeOnlySendsGamePortsToWarp() {
        assertEquals(UdpPolicy.ROUTE_WARP, UdpPolicy.route(UdpPolicy.NORMAL, true, false, 3074, false))
        assertEquals(UdpPolicy.ROUTE_WARP, UdpPolicy.route(UdpPolicy.NORMAL, true, false, 3478, false))
        assertEquals(UdpPolicy.ROUTE_WARP, UdpPolicy.route(UdpPolicy.NORMAL, true, false, 9295, false))
        assertEquals(UdpPolicy.ROUTE_RELAY, UdpPolicy.route(UdpPolicy.NORMAL, true, false, 443, false))
        assertEquals(UdpPolicy.ROUTE_RELAY, UdpPolicy.route(UdpPolicy.NORMAL, true, false, 53, true))
        assertEquals(UdpPolicy.ROUTE_DROP, UdpPolicy.route(UdpPolicy.NORMAL, true, false, 12345, false))
    }

    @Test
    fun relayOnlyNeverRoutesToWarp() {
        for (port in listOf(53, 80, 443, 3074, 3478, 8080, 8443, 9295, 51820)) {
            val route = UdpPolicy.route(UdpPolicy.RELAY_ONLY, true, false, port, port == 53)
            assertFalse("port $port", route == UdpPolicy.ROUTE_WARP)
        }
        assertEquals(UdpPolicy.ROUTE_DROP, UdpPolicy.route(UdpPolicy.RELAY_ONLY, true, false, 3074, false))
        assertEquals(UdpPolicy.ROUTE_RELAY, UdpPolicy.route(UdpPolicy.RELAY_ONLY, true, false, 443, false))
    }

    @Test
    fun batterySaverDowngradesGameToNormal() {
        assertEquals(UdpPolicy.NORMAL, UdpPolicy.effective(UdpPolicy.GAME, true))
        assertEquals(UdpPolicy.GAME, UdpPolicy.effective(UdpPolicy.GAME, false))
        assertEquals(UdpPolicy.RELAY_ONLY, UdpPolicy.effective(UdpPolicy.RELAY_ONLY, true))
        assertEquals(UdpPolicy.ROUTE_RELAY, UdpPolicy.route(UdpPolicy.GAME, true, true, 443, false))
        assertEquals(UdpPolicy.ROUTE_WARP, UdpPolicy.route(UdpPolicy.GAME, true, true, 3074, false))
    }

    @Test
    fun unknownModeFallsBackToNormal() {
        assertEquals(UdpPolicy.NORMAL, UdpPolicy.normalize(null))
        assertEquals(UdpPolicy.NORMAL, UdpPolicy.normalize("wat"))
        assertEquals(UdpPolicy.GAME, UdpPolicy.normalize("GAME"))
        assertEquals(UdpPolicy.GAME, UdpPolicy.normalize("  game "))
        assertEquals(UdpPolicy.ROUTE_WARP, UdpPolicy.route("wat", true, false, 3074, false))
    }

    @Test
    fun gamePortTableCoversConsoleRanges() {
        for (port in listOf(88, 500, 3074, 3478, 3479, 3544, 4500, 9307, 6112, 6119, 19302, 19309)) {
            assertTrue("port $port", UdpPolicy.isGamePort(port))
        }
        for (port in listOf(80, 443, 8080, 6120, 19310, 12345)) {
            assertFalse("port $port", UdpPolicy.isGamePort(port))
        }
    }
}

class UdpWarningTest {

    @Test
    fun warnsWhenGameModeIsOnButRelayIsOff() {
        assertTrue(UdpWarning.relayNeeded(consoleMode = true, udpRelay = false))
    }

    @Test
    fun silentOnceRelayIsOn() {
        assertFalse(UdpWarning.relayNeeded(consoleMode = true, udpRelay = true))
    }

    @Test
    fun silentWhenMasterIsOff() {
        assertFalse(UdpWarning.relayNeeded(consoleMode = false, udpRelay = false))
        assertFalse(UdpWarning.relayNeeded(consoleMode = false, udpRelay = true))
    }
}
