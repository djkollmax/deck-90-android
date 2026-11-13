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
// a 2048-point FFT of the mix with a Hann window). The tap sees the sound when it goes into the audio buffer, a
// quarter of a second or more before it is heard (lead, from the renderer: TimedAudioRenderer), so it measures in
// blocks of ~12 ms, stamps each block with when it will be heard, and the page gets the block being heard now: the
// needles and bars move with the sound, not ahead of it. Buffers are reused: nothing to collect on the audio thread.
@UnstableApi
object Levels : TeeAudioProcessor.AudioBufferSink {
    const val SPECTRUM_BANDS = 28
    private const val N = 2048
    private const val BLOCK = 512                                    // frames per measured block (~12 ms at 44.1 kHz)
    private const val RING = 256                                     // blocks kept: ~3 s, more than any lead
    private var encoding = C.ENCODING_INVALID
    private var channels = 2
    private var rate = 44100

    // when the sound handed over now will be heard, in ms (the renderer's measure, smoothed)
    @Volatile var leadMs = 0.0; private set
    private var leadSeen = false
    fun lead(ms: Double) {
        val v = ms.coerceIn(0.0, 3000.0)
        leadMs = if (!leadSeen) v else leadMs + (v - leadMs) * 0.05
        leadSeen = true
    }

    // the blocks: when each is heard (wall clock, ms), its peaks and RMS, and its spectrum
    private val heardAt = LongArray(RING)
    private val vals = FloatArray(RING * 4)
    private val bandsRing = FloatArray(RING * SPECTRUM_BANDS) { -90f }
    @Volatile private var written = 0L                               // blocks written so far (the next goes at written % RING)

    // the block being filled
    private var bl = 0f; private var br = 0f; private var sl = 0.0; private var sr = 0.0; private var bf = 0
    private val sample = FloatArray(N); private var sampleAt = 0     // the last N samples of the mix, for the FFT
    private var sinceFft = 0
    private var lastBands = FloatArray(SPECTRUM_BANDS) { -90f }
    private val re = DoubleArray(N); private val im = DoubleArray(N)
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding; channels = channelCount.coerceAtLeast(1); rate = sampleRateHz.coerceAtLeast(8000)
        bl = 0f; br = 0f; sl = 0.0; sr = 0.0; bf = 0
        // a new station or a seek: the sound still waiting in the buffer is thrown away, and so are its blocks
        val now = System.currentTimeMillis()
        for (i in 0 until RING) if (heardAt[i] > now) heardAt[i] = Long.MAX_VALUE
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_FLOAT -> 4; else -> return }
        val now = System.currentTimeMillis()
        val frameMs = 1000.0 / rate
        var frame = 0
        while (b.remaining() >= bytes * channels) {
            var mix = 0f
            for (c in 0 until channels) {
                val v = if (bytes == 2) b.short / 32768f else b.float
                val a = abs(v)
                if (c == 0) { bl = max(bl, a); sl += v * v } else if (c == 1) { br = max(br, a); sr += v * v }
                if (c < 2) mix += v
            }
            sample[sampleAt] = mix / minOf(2, channels); sampleAt = (sampleAt + 1) % N
            frame++; bf++
            if (bf == BLOCK) close((now + leadMs + frame * frameMs).toLong())
        }
    }

    // a block done: kept with when it is heard (its end)
    private fun close(heard: Long) {
        if (channels < 2) { br = bl; sr = sl }
        val i = (written % RING).toInt()
        vals[i * 4] = bl.coerceAtMost(1f); vals[i * 4 + 1] = br.coerceAtMost(1f)
        vals[i * 4 + 2] = sqrt(sl / bf).toFloat().coerceAtMost(1f); vals[i * 4 + 3] = sqrt(sr / bf).toFloat().coerceAtMost(1f)
        if (++sinceFft >= 2 || written == 0L) { sinceFft = 0; spectrum(lastBands) }   // ~43 a second
        System.arraycopy(lastBands, 0, bandsRing, i * SPECTRUM_BANDS, SPECTRUM_BANDS)
        heardAt[i] = heard
        written++
        bl = 0f; br = 0f; sl = 0.0; sr = 0.0; bf = 0
    }

    // the last N samples' spectrum, in bands
    private fun spectrum(out: FloatArray) {
        for (i in 0 until N) { re[i] = sample[(sampleAt + i) % N] * window[i]; im[i] = 0.0 }
        fft(re, im)
        val lo = 40.0; val hi = minOf(16000.0, rate / 2.0)
        for (k in 0 until SPECTRUM_BANDS) {
            val f0 = lo * (hi / lo).pow(k.toDouble() / SPECTRUM_BANDS); val f1 = lo * (hi / lo).pow((k + 1.0) / SPECTRUM_BANDS)
            val i0 = (f0 * N / rate).toInt().coerceIn(1, N / 2 - 1); val i1 = (f1 * N / rate).toInt().coerceIn(i0, N / 2 - 1)
            var e = 0.0
            for (i in i0..i1) e = max(e, re[i] * re[i] + im[i] * im[i])
            out[k] = (10 * log10(e / (N * N / 16.0) + 1e-12)).toFloat()   // ≈ dBFS of a sine in that band
        }
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

    // the block being heard at `now`: the newest whose time has come; -1 when nothing has been heard for 400 ms
    private fun heard(now: Long): Int {
        val w = written
        var k = w - 1
        val oldest = maxOf(0L, w - RING + 8)                          // leave room for the writer
        while (k >= oldest) {
            val i = (k % RING).toInt()
            val t = heardAt[i]
            if (t <= now) return if (now - t <= 400) i else -1
            k--
        }
        return -1
    }

    // peaks then RMS: "pl,pr,rl,rr"
    fun now(at: Long = System.currentTimeMillis()): String {
        val i = heard(at); if (i < 0) return "0,0,0,0"
        return "${vals[i * 4]},${vals[i * 4 + 1]},${vals[i * 4 + 2]},${vals[i * 4 + 3]}"
    }
    fun spectrumNow(at: Long = System.currentTimeMillis()): String {
        val i = heard(at); if (i < 0) return ""
        val sb = StringBuilder(SPECTRUM_BANDS * 6)
        for (k in 0 until SPECTRUM_BANDS) { if (k > 0) sb.append(','); sb.append(Math.round(bandsRing[i * SPECTRUM_BANDS + k] * 10) / 10f) }
        return sb.toString()
    }

    // for the tests: a block of samples straight in, heard right away
    internal fun feedForTest(samples: FloatArray, sampleRate: Int, channelCount: Int, lead: Double = 0.0, newStream: Boolean = true) {
        leadSeen = false; lead(lead)
        if (newStream) flush(sampleRate, channelCount, C.ENCODING_PCM_FLOAT)
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { bb.putFloat(it) }; bb.flip()
        sinceFft = 1; handleBuffer(bb)
    }
    internal fun bandsForTest(): FloatArray { val i = ((written - 1) % RING).toInt(); return bandsRing.copyOfRange(i * SPECTRUM_BANDS, (i + 1) * SPECTRUM_BANDS) }
}
