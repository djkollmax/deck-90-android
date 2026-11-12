package com.djkollmax.deck90

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONObject

// ═══════════════════════════════════════
//  THE PHONE: the car stereo's front (the same face and display as Deck 90 on the PC: assets/face), working the
//  radio through the player's session. Its keys: SEL play/pause, BAND the next band (held: A.MEM, then a number
//  keeps the station there), 1–6 the memories (held 7–12), ◀◀ ▶▶ the band's stations, SCAN 8 s of each, VOL ▲ ▼
//  and ATT the phone's volume, DISP the station or its song. The page asks for the state (state()) and the meters
//  (levels()) and sends the keys (key()).
// ═══════════════════════════════════════
@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var store: Store
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var state = "{}"
    private var memo = 0L                                            // A.MEM armed until this time
    private var scanning = false
    private var showSong = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            setBackgroundColor(0xFF000000.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true   // the face never leaves
            }
            addJavascriptInterface(Bridge(), "Deck90Android")
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/face/index.html")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)   // the player's notification
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { refresh() }
            })
            refresh()
        }, MoreExecutors.directExecutor())
        main.post(ticker)
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        controller?.release(); controller = null
        super.onStop()
    }

    // what the face shows, made on the main thread (the page reads it from its own)
    private var seen = -1
    private val ticker = object : Runnable {
        override fun run() { if (Now.version != seen) refresh(); main.postDelayed(this, 250) }
    }

    private fun refresh() {
        seen = Now.version
        val c = controller
        val band = store.band; val slot = store.slot
        val st = store.station(band, slot)
        val playing = c?.isPlaying == true
        val buffering = c?.playbackState == Player.STATE_BUFFERING && c.playWhenReady
        val name = st?.name ?: "—"
        val song = Now.song
        val line = when {
            Now.error.isNotEmpty() && !playing -> Now.error
            buffering -> "SINTONIZANDO…"
            showSong && song.isNotEmpty() -> "$name · $song"
            else -> name
        }
        state = JSONObject()
            .put("band", Bands.label(band)).put("slot", slot).put("freq", st?.freq ?: "")
            .put("name", line).put("station", name).put("song", song)
            .put("playing", playing).put("mono", band == "AM").put("quality", quality(c))
            .put("memo", System.currentTimeMillis() < memo).put("scan", scanning)
            .toString()
    }

    // the stream's codec and bitrate, as the PC shows them (FLAC is HI-FI)
    private fun quality(c: Player?): String {
        val f = c?.currentTracks?.groups?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }?.getTrackFormat(0) ?: return ""
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_OPUS -> "OPUS"; MimeTypes.AUDIO_VORBIS -> "OGG"; else -> ""
        }
        val kbps = if (f.bitrate > 0) f.bitrate / 1000 else 0
        return listOf(codec, if (kbps > 0) kbps.toString() else "").filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun tune(band: String, slot: Int) {
        val c = controller ?: return
        if (store.station(band, slot) == null) { toast("MEMORIA $slot VACÍA"); return }
        store.band = band; store.slot = slot; Now.song = ""; Now.error = ""
        c.setMediaItem(MediaItem.Builder().setMediaId(Store.mediaId(band, slot)).build())
        c.prepare(); c.play(); refresh()
    }

    private fun toast(text: String) = main.post { web.evaluateJavascript("window.faceToast&&faceToast(${JSONObject.quote(text)})", null) }

    // a key from the face (on the main thread)
    private fun key(k: String, hold: Boolean) {
        val c = controller
        if (k != "scan" && scanning) { scanning = false; main.removeCallbacks(scan) }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            k == "sel" -> when {
                c == null -> {}
                c.isPlaying -> c.pause()
                c.mediaItemCount > 0 -> { c.prepare(); c.play() }
                else -> tune(store.band, store.slot)
            }
            k == "band" && hold -> { memo = System.currentTimeMillis() + 5000; toast("A.MEM · tocá un número para guardar la que suena") }
            k == "band" -> {
                val next = Bands.order[(Bands.order.indexOf(store.band) + 1) % Bands.order.size]
                tune(next, 1); toast("BANDA " + Bands.label(next))
            }
            Regex("^m[1-6]$").matches(k) -> {
                val n = k[1].digitToInt() + if (hold) 6 else 0
                if (System.currentTimeMillis() < memo) {
                    memo = 0
                    val now = store.station(store.band, store.slot)
                    if (now != null) { store.keep(store.band, n, now); toast("GUARDADA EN " + Bands.label(store.band) + " · $n"); tune(store.band, n) }
                } else tune(store.band, n)
            }
            k == "next" || k == "prev" -> c?.let { if (k == "next") it.seekToNextMediaItem() else it.seekToPreviousMediaItem(); it.play() }
            k == "scan" -> {
                scanning = !scanning
                if (scanning) { toast("SCAN · 8 s de cada radio, SCAN otra vez para quedarte"); main.postDelayed(scan, 8000) }
                else { main.removeCallbacks(scan); toast("SCAN · te quedás en esta") }
            }
            k == "volup" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            k == "voldown" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            k == "att" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            k == "disp" -> { showSong = !showSong; toast(if (showSong) "DISP · la radio y su tema" else "DISP · solo la radio") }
            k == "mode" -> toast("MODE · el servidor de música llega en la próxima versión")
        }
        web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        refresh()
    }

    private val scan = object : Runnable {
        override fun run() {
            if (!scanning) return
            controller?.let { it.seekToNextMediaItem(); it.play() }
            main.postDelayed(this, 8000)
        }
    }

    // what the page may ask: called on the page's own thread
    private inner class Bridge {
        @JavascriptInterface fun state(): String = state
        @JavascriptInterface fun levels(): String { val (l, r) = Levels.now(); return "$l,$r" }
        @JavascriptInterface fun key(k: String, hold: Boolean) { main.post { this@MainActivity.key(k, hold) } }
        @JavascriptInterface fun version(): String = BuildConfigInfo.version
    }
}
