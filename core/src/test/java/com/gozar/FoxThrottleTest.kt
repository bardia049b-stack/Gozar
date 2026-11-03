package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxThrottleTest {

    private class Clock {
        var current = 1_700_000_000_000L
        val waits = mutableListOf<Long>()

        fun now() = current

        fun sleep(ms: Long) {
            waits.add(ms)
            current += ms
        }

        fun total() = waits.sum()
    }

    private fun throttle(clock: Clock) = FoxThrottle(
        sleep = { clock.sleep(it) },
        now = { clock.now() },
        random = { 0L }
    )

    private fun reply(code: Int, retryAfter: String? = null): FoxReply {
        val headers = if (retryAfter == null) emptyMap() else mapOf("Retry-After" to retryAfter)
        return FoxReply(code, "", headers)
    }

    @Test
    fun retriesRateLimitThenSucceeds() {
        val clock = Clock()
        var calls = 0
        val result = throttle(clock).run {
            calls++
            if (calls < 3) reply(429) else reply(200)
        }
        assertEquals(200, result.code)
        assertEquals(3, calls)
        assertTrue("backoff too short: ${clock.total()}", clock.total() >= 3000)
    }

    @Test
    fun honoursRetryAfterSeconds() {
        val clock = Clock()
        var calls = 0
        val result = throttle(clock).run {
            calls++
            if (calls == 1) reply(429, "7") else reply(200)
        }
        assertEquals(200, result.code)
        assertEquals(2, calls)
        assertTrue("Retry-After ignored: ${clock.total()}", clock.total() >= 7000)
    }

    @Test
    fun capsBackoffAndGivesUp() {
        val clock = Clock()
        var calls = 0
        val result = throttle(clock).run {
            calls++
            reply(429, "99999")
        }
        assertEquals(429, result.code)
        assertEquals(5, calls)
        assertTrue(clock.waits.all { it <= 61000 })
    }

    @Test
    fun doesNotRetryOrdinaryFailures() {
        val clock = Clock()
        var calls = 0
        val result = throttle(clock).run {
            calls++
            reply(401)
        }
        assertEquals(401, result.code)
        assertEquals(1, calls)
    }

    @Test
    fun retriesServiceUnavailable() {
        val clock = Clock()
        var calls = 0
        val result = throttle(clock).run {
            calls++
            if (calls == 1) reply(503) else reply(200)
        }
        assertEquals(200, result.code)
        assertEquals(2, calls)
    }

    @Test
    fun spacesOutSuccessiveLogins() {
        val clock = Clock()
        val limiter = throttle(clock)
        repeat(3) { limiter.run { reply(200) } }
        assertTrue("logins not spaced: ${clock.total()}", clock.total() >= 1500)
    }

    @Test
    fun cooldownDelaysTheNextCall() {
        val clock = Clock()
        val limiter = throttle(clock)
        limiter.cooldown(10000)
        limiter.run { reply(200) }
        assertTrue("cooldown ignored: ${clock.total()}", clock.total() >= 10000)
    }

    @Test
    fun retryAfterRejectsGarbage() {
        assertEquals(0L, FoxThrottle.retryAfterOf(reply(429, "soon")))
        assertEquals(0L, FoxThrottle.retryAfterOf(reply(429, "-5")))
        assertEquals(0L, FoxThrottle.retryAfterOf(reply(429)))
        assertEquals(30000L, FoxThrottle.retryAfterOf(reply(429, "30")))
    }
}
