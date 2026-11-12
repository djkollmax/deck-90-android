package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelsTest {
    @Test fun `a 1 kHz tone on the left gives its peak and RMS, and the spectrum peaks at 1 kHz`() {
        val rate = 44100; val n = 4096
        val s = FloatArray(n * 2) { i -> if (i % 2 == 0) (0.5 * sin(2 * PI * 1000 * (i / 2) / rate)).toFloat() else 0f }
        Levels.feedForTest(s, rate, 2)
        val (pl, pr, rl, rr) = Levels.now().split(",").map { it.toFloat() }
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
}
