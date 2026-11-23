package com.djkollmax.deck90

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
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
//  but the players. The meters don't follow it: Android doesn't let an app hear another's sound.
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
