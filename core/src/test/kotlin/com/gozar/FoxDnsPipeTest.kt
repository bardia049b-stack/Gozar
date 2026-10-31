package com.gozar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxDnsPipeTest {
    private fun query(id: Int) = byteArrayOf(
        0, id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 119, 119, 119, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1
    )

    @Test
    fun fragmentedAndMultipleTcpDnsMessagesAreFramedAndAnswered() {
        val resolver = FoxResolver(0, { _, q -> q.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() } })
        val pipe = FoxDnsPipe(resolver)
        val one = query(1)
        val two = query(2)
        val input = byteArrayOf(0, one.size.toByte()) + one + byteArrayOf(0, two.size.toByte()) + two
        pipe.write(input, 0, 1)
        pipe.write(input, 1, 7)
        pipe.write(input, 8, input.size - 8)
        pipe.finishWrite()
        val result = ByteArray(input.size)
        var position = 0
        while (position < result.size) {
            val count = pipe.read(result, position, minOf(3, result.size - position))
            assertTrue(count > 0)
            position += count
        }
        assertEquals(-1, pipe.read(ByteArray(1), 0, 1))
        assertEquals(one.size, result[1].toInt() and 255)
        assertEquals(1, result[3].toInt() and 255)
        assertEquals(2, result[one.size + 5].toInt() and 255)
        val expected = one.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
        assertArrayEquals(expected, result.copyOfRange(2, one.size + 2))
        pipe.close()
    }
}
