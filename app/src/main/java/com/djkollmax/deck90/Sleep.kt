package com.djkollmax.deck90

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

// ═══════════════════════════════════════
//  SLEEP: the radio switches itself off after 15, 30, 60 or 90 minutes (PWR held steps through them, then off). It
//  lives with the player in the service, so it keeps counting with the screen off or the app closed; the last 10 s
//  the sound fades out, then the player pauses and its volume comes back for the next time.
// ═══════════════════════════════════════
object Sleep {
    val STEPS = listOf(15, 30, 60, 90)
    private const val FADE_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())
    private var player: Player? = null
    @Volatile var at = 0L; private set                               // when it switches off (0: no sleep)
    @Volatile var chosen = 0; private set                            // the minutes chosen last

    fun attach(p: Player?) { player = p; if (p == null) cancel() }

    // the next step: off → 15 → 30 → 60 → 90 → off; the minutes chosen, or 0 when off
    fun step(now: Long = System.currentTimeMillis()): Int {
        val next = if (at == 0L) STEPS.first() else STEPS.getOrNull(STEPS.indexOf(chosen) + 1) ?: 0
        if (next == 0) cancel() else set(next, now)
        return next
    }

    fun set(minutes: Int, now: Long = System.currentTimeMillis()) {
        main.removeCallbacks(tick); player?.volume = 1f
        chosen = minutes; at = now + minutes * 60_000L
        main.post(tick); Now.changed()
    }

    fun cancel() {
        main.removeCallbacks(tick)
        if (at != 0L) player?.volume = 1f
        at = 0L; chosen = 0; Now.changed()
    }

    // whole minutes left, rounded up (0: no sleep)
    fun left(now: Long = System.currentTimeMillis()): Int = if (at == 0L) 0 else ((at - now + 59_999) / 60_000).toInt().coerceAtLeast(1)

    private val tick = object : Runnable {
        override fun run() {
            if (at == 0L) return
            val rest = at - System.currentTimeMillis()
            val p = player
            when {
                rest <= 0 -> { p?.pause(); p?.volume = 1f; at = 0L; chosen = 0; Now.changed(); return }
                rest <= FADE_MS -> p?.volume = (rest.toFloat() / FADE_MS).coerceIn(0f, 1f)
            }
            main.postDelayed(this, if (rest <= FADE_MS + 1000) 100 else 1000)
        }
    }
}
