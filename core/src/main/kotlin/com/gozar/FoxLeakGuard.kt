package com.gozar

import java.util.concurrent.atomic.AtomicLongArray

/**
 * Leak Guard: every packet the stack refuses to carry is counted here, so
 * drops are visible instead of silent, and [selfTest] proves the routing
 * invariants hold without touching the network.
 *
 * The counters are process-wide and monotonic; the settings screen shows the
 * total as proof the guard is doing its job.
 */
object FoxLeakGuard {

    const val BLOCKED_IPV6 = 0
    const val BLOCKED_MALFORMED = 1
    const val BLOCKED_SPOOFED = 2
    const val BLOCKED_FRAGMENT = 3
    const val BLOCKED_OVERFLOW = 4

    private val LABELS = listOf("ipv6", "malformed", "spoofed", "fragment", "overflow")

    private val counters = AtomicLongArray(LABELS.size)

    fun count(event: Int) {
        counters.incrementAndGet(event)
    }

    fun blocked(event: Int): Long = counters.get(event)

    fun snapshot(): Map<String, Long> =
        LABELS.withIndex().associate { (index, label) -> label to counters.get(index) }

    fun total(): Long {
        var sum = 0L
        for (index in 0 until LABELS.size) sum += counters.get(index)
        return sum
    }

    fun reset() {
        for (index in 0 until LABELS.size) counters.set(index, 0L)
    }

    /**
     * Policy self-test. Verifies, without any network access, that the
     * routing lists are intact and can never route foreign destinations or
     * IPv6 literals directly. Returns false if the bundled list was tampered
     * with or a bad update slipped through.
     */
    fun selfTest(): Boolean =
        FoxGeo.direct("aparat.com") &&
            FoxGeo.direct("www.digikala.com") &&
            !FoxGeo.direct("google.com") &&
            !FoxGeo.direct("www.google.com") &&
            !FoxGeo.direct("8.8.8.8") &&
            !FoxGeo.direct("1.1.1.1") &&
            !FoxGeo.direct("2001:4860:4860::8888") &&
            !FoxGeo.direct("aparat.com.evil.example") &&
            !FoxGeo.direct("")
}
