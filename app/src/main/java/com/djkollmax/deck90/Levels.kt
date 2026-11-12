package com.djkollmax.deck90

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

// The sound's level, left and right (0–1 peaks), taken from what the player sends to the speakers: the stereo's
// meters on the phone read it each frame. Held for 60 ms and let fall, as the PC's stereo meters do.
@UnstableApi
object Levels : TeeAudioProcessor.AudioBufferSink {
    @Volatile var left = 0f; private set
    @Volatile var right = 0f; private set
    @Volatile private var at = 0L
    private var encoding = C.ENCODING_INVALID
    private var channels = 2

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding; channels = channelCount
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        var l = 0f; var r = 0f
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = 0
                while (b.remaining() >= 2) {
                    val v = abs(b.short.toInt()) / 32768f
                    if (channels < 2 || i % channels == 0) l = max(l, v) else if (i % channels == 1) r = max(r, v)
                    i++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = 0
                while (b.remaining() >= 4) {
                    val v = abs(b.float)
                    if (channels < 2 || i % channels == 0) l = max(l, v) else if (i % channels == 1) r = max(r, v)
                    i++
                }
            }
            else -> return
        }
        if (channels < 2) r = l
        left = l.coerceAtMost(1f); right = r.coerceAtMost(1f); at = System.currentTimeMillis()
    }

    // what the meters show now: silent when the player hasn't sent anything for a moment (paused, buffering)
    fun now(): Pair<Float, Float> = if (System.currentTimeMillis() - at > 400) 0f to 0f else left to right
}
