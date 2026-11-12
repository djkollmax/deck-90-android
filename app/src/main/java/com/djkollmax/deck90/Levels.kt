package com.djkollmax.deck90

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The sound as the player sends it to the speakers, measured for the phone's meters: each channel's peak and RMS
// (0–1, the stereo's meters and the VU) and a spectrum of SPECTRUM_BANDS bands (dB, 40 Hz – 16 kHz, log-spaced, from
// a 2048-point FFT of the mix with a Hann window, made at most 30 times a second). Read by the page each frame.
@UnstableApi
object Levels : TeeAudioProcessor.AudioBufferSink {
    const val SPECTRUM_BANDS = 28
    private const val N = 2048
    @Volatile private var peakL = 0f; @Volatile private var peakR = 0f
    @Volatile private var rmsL = 0f; @Volatile private var rmsR = 0f
    @Volatile private var at = 0L
    @Volatile private var bands = FloatArray(SPECTRUM_BANDS) { -90f }
    private var encoding = C.ENCODING_INVALID
    private var channels = 2
    private var rate = 44100
    private val ring = FloatArray(N)
    private var ringAt = 0
    private var fftAt = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding; channels = channelCount.coerceAtLeast(1); rate = sampleRateHz.coerceAtLeast(8000)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_FLOAT -> 4; else -> return }
        var pl = 0f; var pr = 0f; var sl = 0.0; var sr = 0.0; var frames = 0
        while (b.remaining() >= bytes * channels) {
            var mix = 0f
            for (c in 0 until channels) {
                val v = if (bytes == 2) b.short / 32768f else b.float
                val a = abs(v)
                if (c == 0) { pl = max(pl, a); sl += v * v } else if (c == 1) { pr = max(pr, a); sr += v * v }
                if (c < 2) mix += v
            }
            ring[ringAt] = mix / minOf(2, channels); ringAt = (ringAt + 1) % N
            frames++
        }
        if (frames == 0) return
        if (channels < 2) { pr = pl; sr = sl }
        peakL = pl.coerceAtMost(1f); peakR = pr.coerceAtMost(1f)
        rmsL = sqrt(sl / frames).toFloat().coerceAtMost(1f); rmsR = sqrt(sr / frames).toFloat().coerceAtMost(1f)
        val now = System.currentTimeMillis()
        at = now
        if (now - fftAt >= 33) { fftAt = now; bands = spectrum() }
    }

    // the last N samples' spectrum, in bands
    private fun spectrum(): FloatArray {
        val re = DoubleArray(N); val im = DoubleArray(N)
        for (i in 0 until N) re[i] = ring[(ringAt + i) % N] * (0.5 - 0.5 * cos(2 * PI * i / (N - 1)))
        fft(re, im)
        val out = FloatArray(SPECTRUM_BANDS)
        val lo = 40.0; val hi = minOf(16000.0, rate / 2.0)
        for (k in 0 until SPECTRUM_BANDS) {
            val f0 = lo * (hi / lo).pow(k.toDouble() / SPECTRUM_BANDS); val f1 = lo * (hi / lo).pow((k + 1.0) / SPECTRUM_BANDS)
            val i0 = (f0 * N / rate).toInt().coerceIn(1, N / 2 - 1); val i1 = (f1 * N / rate).toInt().coerceIn(i0, N / 2 - 1)
            var e = 0.0
            for (i in i0..i1) e = max(e, re[i] * re[i] + im[i] * im[i])
            out[k] = (10 * log10(e / (N * N / 16.0) + 1e-12)).toFloat()   // ≈ dBFS of a sine in that band
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun live() = System.currentTimeMillis() - at <= 400       // nothing sent for a moment: paused, buffering

    // peaks then RMS: "pl,pr,rl,rr"
    fun now(): String = if (!live()) "0,0,0,0" else "$peakL,$peakR,$rmsL,$rmsR"
    fun spectrumNow(): String = if (!live()) "" else bands.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) }

    // for the tests: a block of samples straight in
    internal fun feedForTest(samples: FloatArray, sampleRate: Int, channelCount: Int) {
        flush(sampleRate, channelCount, C.ENCODING_PCM_FLOAT)
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { bb.putFloat(it) }; bb.flip()
        fftAt = 0; handleBuffer(bb)
    }
    internal fun bandsForTest() = bands.copyOf()
}
