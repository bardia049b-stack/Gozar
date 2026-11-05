package com.gozar

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoCoreSettingsTest {

    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun location() = FoxLocation("de", "Germany", "Berlin", "proxy.example.org", 443, true)

    @Test
    fun defaultSettingsLeaveTheNativeCoreOff() {
        assertFalse(Store.coreEnabled(context))
        assertFalse(Store.gameMode(context))
        assertFalse(Store.udpRelay(context))
        assertFalse(Store.warp(context))
    }

    @Test
    fun gameModeSelectsTheNativeCore() {
        Store.setGameMode(context, true)
        assertTrue(Store.coreEnabled(context))
    }

    @Test
    fun anyAdvancedTransportSelectsTheNativeCore() {
        for (name in Store.ADVANCED) {
            context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
            assertFalse(Store.coreEnabled(context))
            Store.setAdvanced(context, name, true)
            assertTrue("$name must select the native core", Store.coreEnabled(context))
        }
    }

    @Test
    fun gameModeEnablesUdpAndWarpInThePayload() {
        Store.setGameMode(context, true)
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        assertTrue(json.getBoolean("game_mode"))
        assertTrue(json.getBoolean("udp"))
        assertTrue(json.getBoolean("warp"))
        assertEquals("proxy.example.org", json.getString("proxy_host"))
        assertEquals(443, json.getInt("proxy_port"))
        assertEquals(1280, json.getInt("mtu"))
        assertEquals("pass-token", json.getString("pass"))
    }

    @Test
    fun plainSessionKeepsUdpOff() {
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        assertFalse(json.getBoolean("udp"))
        assertFalse(json.getBoolean("game_mode"))
        assertFalse(json.getBoolean("warp"))
    }

    @Test
    fun advancedTogglesAndParamsAreSerialised() {
        Store.setAdvanced(context, "doq", true)
        Store.setAdvanced(context, "wss", true)
        Store.setAdvancedParam(context, "doq_server", "dns.example.org:853")
        Store.setAdvancedParam(context, "wss_path", "/tunnel")
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        val advanced = json.getJSONObject("advanced")
        assertTrue(advanced.getBoolean("doq"))
        assertTrue(advanced.getBoolean("wss"))
        assertTrue(advanced.getBoolean("doh_fallback"))
        assertFalse(advanced.has("ech"))
        val params = json.getJSONObject("params")
        assertEquals("dns.example.org:853", params.getString("doq_server"))
        assertEquals("/tunnel", params.getString("wss_path"))
    }

    @Test
    fun transportPayloadCarriesOnlyTransportFields() {
        Store.setAdvanced(context, "ech", true)
        Store.setAdvancedParam(context, "ech_config_list", "AAQBAgME")
        val json = GoSettings.transportJson(context)
        assertTrue(json.getJSONObject("advanced").getBoolean("ech"))
        assertEquals("AAQBAgME", json.getJSONObject("params").getString("ech_config_list"))
        assertFalse(json.has("proxy_host"))
        assertFalse(json.has("pass"))
    }

    @Test
    fun warpEndpointAndIdentityArePassedThrough() {
        Store.setWarp(context, true)
        Store.setWarpEndpoint(context, "162.159.198.2:443")
        Store.setWarpIdentity(context, "{\"id\":\"device-1\",\"private_key\":\"secret\"}")
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        assertEquals("162.159.198.2:443", json.getString("warp_endpoint"))
        assertEquals("device-1", json.getJSONObject("warp_identity").getString("id"))
    }

    @Test
    fun corruptIdentityIsDroppedInsteadOfCrashing() {
        Store.setWarp(context, true)
        Store.setWarpIdentity(context, "not json")
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        assertFalse(json.has("warp_identity"))
    }

    @Test
    fun chainHopsAreForwarded() {
        Store.setChain(context, "socks5://127.0.0.1:1080")
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        assertTrue(json.getJSONArray("chain").length() > 0)
    }

    @Test
    fun backupsSkipTheActiveServerAndCapAtTwo() {
        Fox.servers = listOf(
            location(),
            FoxLocation("nl", "Netherlands", "Amsterdam", "nl.example.org", 443, false),
            FoxLocation("fr", "France", "Paris", "fr.example.org", 443, false),
            FoxLocation("it", "Italy", "Rome", "it.example.org", 443, false)
        )
        val json = JSONObject(GoSettings.build(context, location(), "pass-token"))
        val backups = json.getJSONArray("backups")
        assertEquals(2, backups.length())
        for (index in 0 until backups.length()) {
            assertFalse(backups.getString(index) == "proxy.example.org")
        }
        Fox.servers = emptyList()
    }

    @Test
    fun missingCoreIsReportedInsteadOfThrowing() {
        assertFalse(GoCore.available)
        assertEquals("", GoCore.version())
        assertEquals("{}", GoCore.stats())
        assertFalse(GoCore.running())
        GoCore.stop()
    }

    @Test
    fun killSwitchSettingRoundTrips() {
        assertFalse(Store.killSwitch(context))
        Store.setKillSwitch(context, true)
        assertTrue(Store.killSwitch(context))
    }
}
