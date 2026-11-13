package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTest {
    @Test fun `PWR held steps 15, 30, 60, 90 and then off`() {
        Sleep.cancel()
        val t = 1_000_000L
        assertEquals(listOf(15, 30, 60, 90, 0, 15), List(6) { Sleep.step(t) })
        Sleep.cancel()
    }

    @Test fun `the minutes left round up and the time is counted from the last choice`() {
        Sleep.cancel()
        val t = 5_000_000L
        Sleep.set(30, t)
        assertEquals(t + 30 * 60_000L, Sleep.at)
        assertEquals(30, Sleep.left(t))
        assertEquals(30, Sleep.left(t + 1))                         // 29 min 59.999 s: still 30
        assertEquals(1, Sleep.left(t + 29 * 60_000L + 30_000))       // half a minute: 1
        Sleep.cancel()
        assertEquals(0, Sleep.left(t))
    }
}
