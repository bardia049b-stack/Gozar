package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FoxConfigTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun defaultsToNormalWithEverythingOff() {
        val config = FoxConfig()
        assertEquals(UdpPolicy.NORMAL, config.udpMode)
        assertFalse(config.consoleMode)
        assertFalse(config.batterySaver)
        assertEquals(FoxConfig.DIRECT, config.warpVia)
    }

    @Test
    fun everyModeRoundTripsThroughJson() {
        for (mode in listOf(UdpPolicy.NORMAL, UdpPolicy.GAME, UdpPolicy.RELAY_ONLY)) {
            val config = FoxConfig(udpMode = mode, consoleMode = true, batterySaver = true, warpVia = FoxConfig.RELAY)
            val restored = FoxConfig.fromJson(config.toJson())
            assertEquals(mode, restored.udpMode)
            assertTrue(restored.consoleMode)
            assertTrue(restored.batterySaver)
            assertEquals(FoxConfig.RELAY, restored.warpVia)
        }
    }

    @Test
    fun savesAndLoadsFromDisk() {
        val dir = folder.newFolder()
        val config = FoxConfig(udpMode = UdpPolicy.GAME, consoleMode = true)
        assertTrue(FoxConfig.save(dir, config))
        assertTrue(File(dir, FoxConfig.FILE).isFile)
        val loaded = FoxConfig.load(dir)
        assertEquals(UdpPolicy.GAME, loaded.udpMode)
        assertTrue(loaded.consoleMode)
    }

    @Test
    fun missingFileLoadsDefaults() {
        val loaded = FoxConfig.load(folder.newFolder())
        assertEquals(UdpPolicy.NORMAL, loaded.udpMode)
        assertFalse(loaded.consoleMode)
    }

    @Test
    fun corruptJsonLoadsDefaults() {
        assertEquals(UdpPolicy.NORMAL, FoxConfig.fromJson("{not json").udpMode)
        assertEquals(UdpPolicy.NORMAL, FoxConfig.fromJson("").udpMode)
        assertEquals(UdpPolicy.NORMAL, FoxConfig.fromJson(null).udpMode)
    }

    @Test
    fun unknownModeIsNormalisedOnLoad() {
        val restored = FoxConfig.fromJson("""{"udpMode":"broken","consoleMode":true}""")
        assertEquals(UdpPolicy.NORMAL, restored.udpMode)
        assertTrue(restored.consoleMode)
    }

    @Test
    fun jsonUsesTheKeyTheDesktopCoreReads() {
        val text = FoxConfig(udpMode = UdpPolicy.GAME).toJson()
        assertTrue(text.contains("\"udpMode\":\"game\""))
    }

    @Test
    fun overwritingKeepsTheLatestValue() {
        val dir = folder.newFolder()
        FoxConfig.save(dir, FoxConfig(udpMode = UdpPolicy.GAME))
        FoxConfig.save(dir, FoxConfig(udpMode = UdpPolicy.RELAY_ONLY))
        assertEquals(UdpPolicy.RELAY_ONLY, FoxConfig.load(dir).udpMode)
    }
}
