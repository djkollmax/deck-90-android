package com.djkollmax.deck90

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

// ═══════════════════════════════════════
//  AUX (0.1.20): the music another app of the phone plays (Spotify, YouTube Music, a podcast app…), as a car
//  stereo's auxiliary input: its song on the display, SEL plays and pauses it, ◀◀ ▶▶ skip. Android shows one app
//  another's player only to an app the person lets read notifications (AuxListener); nothing of them is read here
//  but the players. The meters (0.1.22, AuxMeter): only with the microphone permission, from what the tablet plays.
// ═══════════════════════════════════════
class AuxListener : NotificationListenerService()

object Aux {
    fun granted(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    // the permission's screen in the phone's settings (Deck 90 in the list, switched on by the person)
    fun ask(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    // the other apps' players: the one playing, else the last one that played (Android lists them newest first)
    fun player(ctx: Context): MediaController? {
        if (!granted(ctx)) return null
        val all = runCatching {
            (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(ctx, AuxListener::class.java))
        }.getOrNull().orEmpty().filter { it.packageName != ctx.packageName }
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    private fun appName(ctx: Context, pkg: String) = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // what the face shows: allowed or not, the app, its song, playing or not, where in it
    fun state(ctx: Context): JSONObject {
        val o = JSONObject().put("granted", granted(ctx))
        val p = player(ctx) ?: return o.put("app", "")
        val m = p.metadata
        val st = p.playbackState
        val text = { k: String -> m?.getString(k)?.trim().orEmpty() }
        val pos = st?.let { s ->
            if (s.state == PlaybackState.STATE_PLAYING) s.position + ((android.os.SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            else s.position
        } ?: 0L
        return o.put("app", appName(ctx, p.packageName)).put("title", text(MediaMetadata.METADATA_KEY_TITLE))
            .put("artist", text(MediaMetadata.METADATA_KEY_ARTIST).ifEmpty { text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) })
            .put("album", text(MediaMetadata.METADATA_KEY_ALBUM))
            .put("playing", st?.state == PlaybackState.STATE_PLAYING)
            .put("pos", (pos / 1000).coerceAtLeast(0)).put("len", ((m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000).coerceAtLeast(0))
    }

    // SEL, ◀◀ ▶▶ and PWR on the other app's player; false: there's none
    fun key(ctx: Context, k: String): Boolean {
        val p = player(ctx) ?: return false
        val t = p.transportControls
        when (k) {
            "toggle" -> if (p.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }
}

// ═══════════════════════════════════════
//  AUX's meters (0.1.22). Android doesn't hand an app another app's sound, but its Visualizer hears the mix the
//  tablet plays (session 0, the music visualizers' way), with the microphone permission (nothing is recorded:
//  Android files it under the microphone). It is coarse: a snapshot of 1024 samples of 8 bits, 20 times a second,
//  in mono, after the tablet's volume. From each one the peak, the RMS and the spectrum in Levels' bands (the same
//  dB, so the VU and ESPECTRO read it as they read the radio). Some tablets give silence (protected sound, some
//  outputs): then the meters stay still, and the person is told once.
// ═══════════════════════════════════════
object AuxMeter {
    private const val N = 1024
    private var vis: Visualizer? = null
    @Volatile var on = false; private set
    @Volatile private var levels = "0,0,0,0"
    @Volatile private var bands = ""
    @Volatile private var at = 0L                                   // when the last snapshot came
    @Volatile var heardAt = 0L; private set                         // the last one that wasn't silence
    var failed = false; private set                                 // the tablet doesn't give it (no engine)
    private val x = FloatArray(N)
    private val re = DoubleArray(N); private val im = DoubleArray(N)
    private val window = DoubleArray(N) { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (N - 1)) }
    private val out = FloatArray(Levels.SPECTRUM_BANDS)

    // started when AUX is on screen and allowed, stopped otherwise (idempotent: the ticker calls it)
    fun want(yes: Boolean) { if (yes) start() else stop() }
    private fun start() {
        if (vis != null || failed) return
        vis = runCatching {
            Visualizer(0).apply {
                enabled = false
                val range = Visualizer.getCaptureSizeRange()
                captureSize = Integer.highestOneBit(N.coerceIn(range[0], range[1]))
                scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, wave: ByteArray, rate: Int) { take(wave, rate / 1000) }
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {}
                }, Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }
        }.onFailure { failed = true }.getOrNull()
        on = vis != null
    }
    fun stop() {
        vis?.let { v -> runCatching { v.enabled = false }; runCatching { v.release() } }
        vis = null; on = false; at = 0L
    }

    // one snapshot: 8-bit unsigned samples (128 is silence)
    internal fun take(wave: ByteArray, rateHz: Int, now: Long = System.currentTimeMillis()) {
        val n = minOf(N, wave.size); if (n < 64) return
        var peak = 0f; var sum = 0.0
        for (i in 0 until N) {
            val v = if (i < n) ((wave[i].toInt() and 0xff) - 128) / 128f else 0f
            x[i] = v; val a = kotlin.math.abs(v); if (a > peak) peak = a; sum += v * v
        }
        val rms = kotlin.math.sqrt(sum / n).toFloat()
        if (peak > 1.5f / 128) heardAt = now
        levels = "$peak,$peak,$rms,$rms"
        for (i in 0 until N) { re[i] = x[i] * window[i]; im[i] = 0.0 }
        Levels.fft(re, im)
        val rate = rateHz.coerceAtLeast(8000).toDouble()
        val lo = 40.0; val hi = minOf(16000.0, rate / 2)
        for (k in 0 until Levels.SPECTRUM_BANDS) {
            val f0 = lo * Math.pow(hi / lo, k.toDouble() / Levels.SPECTRUM_BANDS); val f1 = lo * Math.pow(hi / lo, (k + 1.0) / Levels.SPECTRUM_BANDS)
            val i0 = (f0 * N / rate).toInt().coerceIn(1, N / 2 - 1); val i1 = (f1 * N / rate).toInt().coerceIn(i0, N / 2 - 1)
            var e = 0.0
            for (i in i0..i1) e = maxOf(e, re[i] * re[i] + im[i] * im[i])
            out[k] = (10 * kotlin.math.log10(e / (N.toDouble() * N / 16) + 1e-12)).toFloat()
        }
        bands = out.joinToString(",") { (Math.round(it * 10) / 10f).toString() }
        at = now
    }

    // as Levels.now() and spectrumNow(): nothing when no snapshot came for 400 ms
    fun now(t: Long = System.currentTimeMillis()) = if (t - at <= 400) levels else "0,0,0,0"
    fun spectrumNow(t: Long = System.currentTimeMillis()) = if (t - at <= 400) bands else ""
}
