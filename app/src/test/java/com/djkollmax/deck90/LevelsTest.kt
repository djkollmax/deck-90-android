package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelsTest {
    private val rate = 44100
    private fun tone(n: Int, left: Double, right: Double) =
        FloatArray(n * 2) { i -> ((if (i % 2 == 0) left else right) * sin(2 * PI * 1000 * (i / 2) / rate)).toFloat() }

    @Test fun `a 1 kHz tone on the left gives its peak and RMS, and the spectrum peaks at 1 kHz`() {
        val t0 = System.currentTimeMillis()
        Levels.feedForTest(tone(4096, 0.5, 0.0), rate, 2)
        val (pl, pr, rl, rr) = Levels.now(t0 + 150).split(",").map { it.toFloat() }
        assertEquals(0.5f, pl, 0.01f); assertEquals(0f, pr, 0.001f)
        assertEquals(0.354f, rl, 0.01f); assertEquals(0f, rr, 0.001f)
        val b = Levels.bandsForTest()
        val top = b.indices.maxByOrNull { b[it] }!!
        val lo = 40.0; val hi = 16000.0; val k = Levels.SPECTRUM_BANDS
        val f0 = lo * Math.pow(hi / lo, top.toDouble() / k); val f1 = lo * Math.pow(hi / lo, (top + 1.0) / k)
        assertTrue("the loudest band holds 1 kHz: $f0–$f1", 1000.0 in f0 * 0.97..f1 * 1.03)
        assertTrue("about −12 dB (half the full scale, mixed with silence): ${b[top]}", b[top] in -20f..-6f)
        assertTrue("far bands quiet", b[0] < b[top] - 30)
    }

    @Test fun `the meters show the sound when it is heard, not when it goes into the buffer`() {
        val t0 = System.currentTimeMillis()
        Levels.feedForTest(tone(1024, 0.0, 0.0), rate, 2)            // silence first, heard now
        Levels.feedForTest(tone(1024, 0.8, 0.8), rate, 2, lead = 300.0, newStream = false)   // the same stream: heard 300 ms on
        val before = Levels.now(t0 + 100).split(",")[0].toFloat()
        val after = Levels.now(t0 + 380).split(",")[0].toFloat()
        assertEquals("before it is heard: still the silence", 0f, before, 0.001f)
        assertEquals("once it is heard: the tone", 0.8f, after, 0.01f)
        assertEquals("long after: nothing", "0,0,0,0", Levels.now(t0 + 2000))
    }

    @Test fun `a new station drops the old one's blocks that were still to be heard`() {
        val t0 = System.currentTimeMillis()
        Levels.feedForTest(tone(1024, 0.2, 0.2), rate, 2)            // heard now (its ~23 ms)
        Thread.sleep(40)
        Levels.feedForTest(tone(1024, 0.9, 0.9), rate, 2, lead = 300.0, newStream = false)   // queued for later, then the station changes:
        Levels.feedForTest(FloatArray(0), rate, 2)                   // flush, nothing new yet
        assertEquals("the queued loud blocks never show", 0.2f, Levels.now(t0 + 380).split(",")[0].toFloat(), 0.01f)
    }
}
