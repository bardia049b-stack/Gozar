package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxAdaptiveTest {
    @Test
    fun transportHealthIsScopedToEndpointAndRecovers() {
        var clock = 1L
        val paths = FoxAdaptive { clock }
        assertTrue(paths.order("a").first())
        paths.failed("a", true)
        assertFalse(paths.order("a").first())
        assertTrue(paths.order("b").first())
        clock += 10_001
        assertTrue(paths.order("a").first())
    }

    @Test
    fun samplesBothAndPrefersAConsistentlyFasterHealthyPath() {
        val paths = FoxAdaptive { 1L }
        paths.order("a")
        paths.success("a", true, 200)
        paths.success("a", false, 40)
        assertFalse(paths.order("a").first())
        val choices = (1..24).map { paths.order("a").first() }
        assertTrue(choices.any { it })
        assertTrue(choices.count { !it } >= 20)
        assertEquals(2, paths.order("a").size)
    }
}
