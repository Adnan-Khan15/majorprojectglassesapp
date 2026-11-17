package com.smartglasses.app

import com.smartglasses.app.ble.SeqTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class SeqTrackerTest {
    @Test
    fun countsGapsIncludingWrapAround() {
        val t = SeqTracker()
        listOf(65533, 65534, 0, 1, 4).forEach { t.add(it, 236) }  // 65535, 2, 3 lost
        assertEquals(5, t.frames)
        assertEquals(3, t.missing)
        assertEquals(5 * 236L, t.bytes)
        assertEquals(37.5, t.lossPercent, 0.01)
    }
}
