package com.gozar

internal class FoxAdaptive(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private class Measure(var average: Double = 0.0, var successes: Int = 0, var failures: Int = 0, var until: Long = 0)
    private class State(val h2: Measure = Measure(), val h1: Measure = Measure(), var count: Int = 0)
    private val states = LinkedHashMap<String, State>()

    @Synchronized
    fun clear() = states.clear()

    @Synchronized
    fun order(key: String): List<Boolean> {
        if (states.size >= 32 && key !in states) states.remove(states.keys.first())
        val state = states.getOrPut(key) { State() }
        state.count++
        val time = now()
        val h2Ready = state.h2.until <= time
        val h1Ready = state.h1.until <= time
        val preferH2 = when {
            h2Ready && !h1Ready -> true
            h1Ready && !h2Ready -> false
            !h2Ready && !h1Ready -> true
            state.h2.successes == 0 -> true
            state.h1.successes == 0 -> true
            state.h1.average < state.h2.average * 0.8 -> false
            else -> true
        }
        val first = if (h1Ready && h2Ready && state.count % 12 == 0) !preferH2 else preferH2
        return listOf(first, !first)
    }

    @Synchronized
    fun success(key: String, h2: Boolean, elapsed: Long) {
        val state = states.getOrPut(key) { State() }
        val measure = if (h2) state.h2 else state.h1
        val value = elapsed.coerceAtLeast(1).toDouble()
        measure.average = if (measure.successes == 0) value else measure.average * 0.8 + value * 0.2
        measure.successes++
        measure.failures = 0
        measure.until = 0
    }

    @Synchronized
    fun failed(key: String, h2: Boolean) {
        val state = states.getOrPut(key) { State() }
        val measure = if (h2) state.h2 else state.h1
        measure.failures = (measure.failures + 1).coerceAtMost(4)
        val delay = when (measure.failures) {
            1 -> 10_000L
            2 -> 30_000L
            else -> 120_000L
        }
        measure.until = now() + delay
    }
}
