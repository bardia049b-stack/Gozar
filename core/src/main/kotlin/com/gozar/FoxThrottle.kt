package com.gozar

import java.util.concurrent.Semaphore
import kotlin.math.min
import kotlin.random.Random

class FoxThrottle(
    private val minimumGapMs: Long = 750L,
    private val maximumBackoffMs: Long = 60000L,
    private val attempts: Int = 5,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound) }
) {

    private val gate = Semaphore(1, true)
    private var lastStart = 0L
    private var blockedUntil = 0L

    fun <T> run(task: () -> T): T {
        gate.acquire()
        try {
            var round = 0
            while (true) {
                val wait = maxOf(blockedUntil, lastStart + minimumGapMs) - now()
                if (wait > 0) sleep(wait)
                lastStart = now()
                val result = task()
                val status = statusOf(result)
                if (status != 429 && status != 503) {
                    blockedUntil = 0L
                    return result
                }
                round++
                if (round >= attempts) return result
                val retryAfter = retryAfterOf(result)
                val exponential = min(maximumBackoffMs, 1000L shl min(round - 1, 16))
                val delay = min(maximumBackoffMs, maxOf(retryAfter, exponential)) + random(400L)
                blockedUntil = now() + delay
                sleep(delay)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } finally {
            gate.release()
        }
    }

    fun cooldown(ms: Long) {
        gate.acquire()
        try {
            blockedUntil = maxOf(blockedUntil, now() + ms)
        } finally {
            gate.release()
        }
    }

    companion object {
        fun statusOf(value: Any?): Int = when (value) {
            is FoxReply -> value.code
            is Int -> value
            else -> 0
        }

        fun retryAfterOf(value: Any?): Long {
            val reply = value as? FoxReply ?: return 0L
            val text = reply.header("Retry-After")?.trim() ?: return 0L
            val seconds = text.toLongOrNull()
            if (seconds != null) return (seconds.coerceIn(0, 3600)) * 1000L
            return try {
                val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(text)
                val instant = java.time.Instant.from(date)
                (instant.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
            } catch (_: Exception) {
                0L
            }
        }
    }
}
