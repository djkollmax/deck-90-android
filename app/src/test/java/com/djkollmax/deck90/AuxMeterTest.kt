package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class AuxMeterTest {
    // a Visualizer snapshot: 8-bit unsigned, 128 the silence
    private fun wave(freq: Double, amp: Double, rate: Int = 44100) =
        ByteArray(1024) { (128 + (amp * 127 * sin(2 * PI * freq * it / rate)).roundToInt()).coerceIn(0, 255).toByte() }

    @Test fun `a sine's peak and RMS, the same on both needles`() {
        AuxMeter.take(wave(1000.0, 0.5), 44100, now = 10_000)
        val (pl, pr, rl, rr) = AuxMeter.now(10_000).split(',').map { it.toFloat() }
        assertEquals(0.5f, pl, 0.02f); assertEquals(pl, pr)
        assertEquals(0.354f, rl, 0.02f); assertEquals(rl, rr)
    }

    @Test fun `the spectrum has the sine in its band, at its level in dB as the radio's`() {
        AuxMeter.take(wave(1000.0, 0.5), 44100, now = 20_000)
        val b = AuxMeter.spectrumNow(20_000).split(',').map { it.toFloat() }
        assertEquals(Levels.SPECTRUM_BANDS, b.size)
        val k = (0 until Levels.SPECTRUM_BANDS).first { 40.0 * (16000.0 / 40).pow((it + 1.0) / Levels.SPECTRUM_BANDS) > 1000 }
        assertEquals(20 * log10(0.5), b[k].toDouble(), 2.0)
        assertTrue(b.withIndex().filter { kotlin.math.abs(it.index - k) > 2 }.all { it.value < b[k] - 20 })
    }

    @Test fun `silence doesn't count as heard, and an old snapshot reads as nothing`() {
        AuxMeter.take(wave(1000.0, 0.5), 44100, now = 30_000)
        val heard = AuxMeter.heardAt
        AuxMeter.take(ByteArray(1024) { 128.toByte() }, 44100, now = 31_000)
        assertEquals(heard, AuxMeter.heardAt)
        assertEquals("0,0,0,0", AuxMeter.now(31_500))
        assertEquals("", AuxMeter.spectrumNow(31_500))
    }
}
