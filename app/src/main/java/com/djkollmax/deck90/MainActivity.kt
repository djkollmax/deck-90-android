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
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════
//  THE PHONE: the car stereo's front (the same face and display as Deck 90 on the PC: assets/face), working the
//  radio through the player's session, its keys as on the PC (src/ui/stereo/stereo.js): SEL play/pause, BAND the
//  next band (held: A.MEM, then a number keeps the station there), 1–6 the memories (held 7–12), ◀◀ ▶▶ the band's
//  stations, SCAN 8 s of each (held: any station in the world, the face's search), VOL ▲ one step and ▼ three, ATT
//  silence (the volume big on the display for 2 s), PWR off and on (held: SLEEP, 15 → 30 → 60 → 90 minutes → off). DISP, HDB and SDM are the face's own. The page
//  asks for the state (state()), the meters (levels(), spectrum()) and searches (search()), and sends the keys
//  (key()) and the station chosen (tune()).
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
    private var power = true
    private var flash = ""; private var flashUntil = 0L              // "VOL 40" or "ATT" on the display, 2 s
    @Volatile private var wx: JSONObject? = null                     // the weather for DISP TEMPERATURA

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
            settings.domStorageEnabled = true                        // the face keeps its SDM, HDB, DISP and views
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

    // out of sight (another app, the screen off) the face stops drawing and asking: the radio goes on in the service
    override fun onResume() { super.onResume(); web.onResume(); web.resumeTimers() }
    override fun onPause() { web.onPause(); web.pauseTimers(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

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
        main.post(weather)
    }

    override fun onStop() {
        main.removeCallbacks(ticker); main.removeCallbacks(weather)
        controller?.release(); controller = null
        super.onStop()
    }

    // what the face shows, made on the main thread (the page reads it from its own)
    private var seen = -1
    private val ticker = object : Runnable {
        override fun run() { if (Now.version != seen || flashUntil > 0 || Sleep.at != 0L) refresh(); main.postDelayed(this, 250) }
    }

    private fun refresh() {
        seen = Now.version
        val c = controller
        val tuned = store.onTuned
        val band = store.band; val slot = if (tuned) 0 else store.slot
        val st = store.current()
        val playing = c?.isPlaying == true
        if (playing) power = true
        val buffering = c?.playbackState == Player.STATE_BUFFERING && c.playWhenReady
        val now = System.currentTimeMillis()
        if (flashUntil in 1 until now) { flash = ""; flashUntil = 0 }
        state = JSONObject()
            .put("band", Bands.label(band)).put("slot", slot).put("freq", st?.freq ?: "").put("tuned", tuned)
            .put("station", st?.name ?: "").put("song", Now.song).put("cc", st?.cc ?: "")
            .put("playing", playing).put("buffering", buffering).put("error", if (playing) "" else Now.error)
            .put("mono", band == "AM" && !tuned).put("quality", quality(c))
            .put("memo", now < memo).put("scan", scanning).put("power", power).put("flash", flash).put("sleep", Sleep.left(now))
            .put("memories", JSONArray(store.memories(band).map { it?.name ?: "" }))
            .put("keepBand", Bands.label(keepBand()))
            .put("town", if (band == Bands.LOCAL) store.localAt()?.optString("town") ?: "" else "")
            .put("wx", wx ?: JSONObject.NULL)
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
        store.band = band; store.slot = slot; store.onTuned = false; Now.song = ""; Now.error = ""; power = true
        c.setMediaItem(MediaItem.Builder().setMediaId(Store.mediaId(band, slot)).build())
        c.prepare(); c.play(); refresh()
    }

    // ◀◀ ▶▶ and SCAN: the band's next memory; from a station found in the world, the band's first or last
    private fun step(dir: Int) {
        val c = controller ?: return
        if (!store.onTuned && c.mediaItemCount > 1) { if (dir > 0) c.seekToNextMediaItem() else c.seekToPreviousMediaItem(); c.play(); return }
        val full = (1..Bands.SLOTS).filter { store.station(store.band, it) != null }
        if (full.isEmpty()) return
        val from = if (store.onTuned) (if (dir > 0) 0 else Bands.SLOTS + 1) else store.slot
        tune(store.band, (if (dir > 0) full.firstOrNull { it > from } ?: full.first() else full.lastOrNull { it < from } ?: full.last()))
    }

    private fun toast(text: String) = main.post { web.evaluateJavascript("window.faceToast&&faceToast(${JSONObject.quote(text)})", null) }

    private fun volume(audio: AudioManager) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val muted = Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC)
        flash = if (muted) "ATT" else "VOL " + Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
        flashUntil = System.currentTimeMillis() + 2000
    }

    // a key from the face (on the main thread)
    private fun key(k: String, hold: Boolean) {
        val c = controller
        if (k != "scan" && scanning) { scanning = false; main.removeCallbacks(scan) }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            k == "pwr" && hold -> {
                val m = Sleep.step()
                flash = if (m > 0) "SLEEP $m" else "SLEEP OFF"; flashUntil = System.currentTimeMillis() + 2000
                toast(if (m > 0) "SLEEP · la radio se apaga sola en $m minutos, a las " +
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(Sleep.at)) +
                    " (PWR apretado otra vez: más tiempo o sin apagado)"
                    else "SLEEP · sin apagado automático")
            }
            k == "pwr" -> {
                power = !power
                if (!power) Sleep.cancel()
                if (power) { if (c != null && c.mediaItemCount > 0) { c.prepare(); c.play() } else tune(store.band, store.slot) }
                else c?.pause()
            }
            k == "sel" -> when {
                c == null -> {}
                c.isPlaying -> { c.pause(); toast("RADIO · pausa") }
                c.mediaItemCount > 0 -> { power = true; c.prepare(); c.play() }
                store.onTuned -> store.tunedItem()?.let { power = true; c.setMediaItem(it); c.prepare(); c.play() }
                else -> tune(store.band, store.slot)
            }
            k == "band" && hold -> {
                if (store.current() == null) { toast("Primero sintonizá una radio y después BAND apretado y el número"); return }
                memo = System.currentTimeMillis() + 6000; toast("A.MEM · tocá el 1–6 donde guardarla (mantenelo apretado para el 7–12)")
            }
            k == "band" -> {
                val next = if (store.band == Bands.LOCAL) store.lastBand else Bands.order[(Bands.order.indexOf(store.band) + 1) % Bands.order.size]
                tune(next, 1); toast("BAND · " + Bands.label(next) + " · doce memorias (1–6, y 7–12 manteniendo apretado)")
            }
            Regex("^m[1-6]$").matches(k) -> {
                val n = k[1].digitToInt() + if (hold) 6 else 0
                if (System.currentTimeMillis() < memo) { memo = 0; keep(n) } else tune(store.band, n)
            }
            // the big keys standing up: a band by name, a memory 1–12, a memory held keeps the one playing there
            k == "band:${Bands.LOCAL}" -> loc(false)
            k.startsWith("band:") -> k.removePrefix("band:").takeIf { it in Bands.order }?.let { b -> tune(b, 1); toast("BAND · " + Bands.label(b)) }
            Regex("^mem(\\d{1,2})$").matches(k) -> tune(store.band, k.removePrefix("mem").toInt().coerceIn(1, Bands.SLOTS))
            Regex("^keep(\\d{1,2})$").matches(k) -> keep(k.removePrefix("keep").toInt().coerceIn(1, Bands.SLOTS))
            k == "next" || k == "prev" -> step(if (k == "next") 1 else -1)
            k == "scan" -> {
                scanning = !scanning
                if (scanning) { toast("SCAN · 8 s de cada radio, SCAN otra vez para quedarte; apretado, buscás cualquier radio"); main.postDelayed(scan, 8000) }
                else { main.removeCallbacks(scan); toast("SCAN · te quedás en esta") }
            }
            k == "volup" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); volume(audio) }
            k == "voldown" -> { repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }; volume(audio) }
            k == "att" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0); volume(audio)
                if (flash == "ATT") toast("ATT · silencio (otra vez para volver)")
            }
            k == "mode" -> toast("MODE · RADIO por internet · el servidor de música llega en la próxima versión")
            k == "eject" || k == "slot" -> toast("Este estéreo no tiene CD: la radio es por internet")
            k == "weather" -> wantWeather()
            k == "loc" -> loc(hold)
        }
        web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        refresh()
    }

    // the station playing kept in the band's memory n (A.MEM and a number, or a big key held), and played from there
    // (playing in LOCAL, it goes to the band BAND was on: LOCAL is made again by LOC)
    private fun keep(n: Int) {
        val now = store.current() ?: run { toast("Primero sintonizá una radio"); return }
        val band = keepBand()
        store.keep(band, n, now); toast("GUARDADA EN " + Bands.label(band) + " · $n")
        store.onTuned = false; tune(band, n)
    }

    private fun keepBand() = if (store.band == Bands.LOCAL) store.lastBand else store.band

    // LOC: the radios where the phone is. A touch plays LOCAL (found again if the phone has moved or it's old) or,
    // already on it, goes back to the band before; held, the whole list of the place in the search
    private var pendingLoc: Boolean? = null
    private fun loc(hold: Boolean) {
        if (!hold && store.band == Bands.LOCAL && !store.onTuned) { tune(store.lastBand, store.lastSlot); toast("LOC · de vuelta a " + Bands.label(store.lastBand)); return }
        if (!located()) {
            pendingLoc = hold
            toast("LOC · para las radios de donde estés, Deck 90 pide tu ubicación aproximada")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            return
        }
        if (hold) toast("LOC · buscando las radios de la zona…")
        whereNow { lat, lon ->
            if (!hold && !Local.stale(store, lat, lon)) { toLocal(); return@whereNow }
            if (!hold) toast("LOC · buscando las radios de la zona…")
            Thread {
                val (town, found) = runCatching { Local.refresh(this, store, lat, lon) }.getOrDefault("" to emptyList())
                main.post {
                    if (hold) { nearList(town, found); return@post }
                    if (found.isEmpty()) toast("LOC · no encontré radios cerca. Probá con SCAN apretado, por país")
                    else toLocal()
                }
            }.start()
        }
    }
    private fun toLocal() {
        if (store.band != Bands.LOCAL) store.lastSlot = store.slot
        tune(Bands.LOCAL, 1)
        val town = store.localAt()?.optString("town").orEmpty()
        toast("LOCAL · " + (if (town.isNotEmpty()) "las radios de $town" else "las radios de la zona") + " · LOC otra vez para volver; apretado, la lista entera")
    }
    // LOC held: the search opened on the place's radios
    private fun nearList(town: String, found: List<Found>) {
        val json = JSONArray(found.map { it.toJson() }).toString()
        web.evaluateJavascript("window.faceNear&&faceNear(${JSONObject.quote(town)},$json)", null)
    }

    private val scan = object : Runnable {
        override fun run() {
            if (!scanning) return
            step(1)
            main.postDelayed(this, 8000)
        }
    }

    // the weather for DISP (Open-Meteo: free, no key, CC BY 4.0) where the phone is: its approximate location (the
    // city is enough), asked for the first time DISP shows the temperature; every 15 minutes, so it follows a trip. The
    // place is rounded to about a kilometre before it leaves the phone; its name comes from the phone's own geocoder.
    // Without the permission, or with location off, the last place known is used, or none: DISP says NO WEATHER.
    private fun located() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val weather = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (located()) whereNow { lat, lon -> fetchWeather(lat, lon) }
            else store.lastPlace()?.let { (lat, lon) -> fetchWeather(lat, lon) }
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun whereNow(then: (Double, Double) -> Unit) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val known = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val fresh = known != null && System.currentTimeMillis() - known.time < 30 * 60_000L
        if (fresh) { then(known!!.latitude, known.longitude); return }
        val provider = android.location.LocationManager.NETWORK_PROVIDER
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) {
            (known?.let { it.latitude to it.longitude } ?: store.lastPlace())?.let { (a, b) -> then(a, b) }; return
        }
        androidx.core.location.LocationManagerCompat.getCurrentLocation(lm, provider, null as android.os.CancellationSignal?,
            androidx.core.content.ContextCompat.getMainExecutor(this)) { loc ->
            val p = loc?.let { it.latitude to it.longitude } ?: known?.let { it.latitude to it.longitude } ?: store.lastPlace()
            p?.let { (a, b) -> then(a, b) }
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        val la = Math.round(lat * 100) / 100.0; val lo = Math.round(lon * 100) / 100.0   // about a kilometre
        store.lastPlace(la, lo)
        Thread {
            runCatching {
                val c = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                    "&current=temperature_2m&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Deck90-Android/" + BuildConfigInfo.version)
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val d = o.getJSONObject("daily")
                wx = JSONObject().put("t", o.getJSONObject("current").getDouble("temperature_2m"))
                    .put("max", d.getJSONArray("temperature_2m_max").getDouble(0)).put("min", d.getJSONArray("temperature_2m_min").getDouble(0))
                    .put("place", placeName(la, lo))
                main.post { refresh() }
            }
        }.start()
    }

    // the town's name, from the phone (Android's geocoder); "" when it has none
    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String = runCatching {
        if (!android.location.Geocoder.isPresent()) return ""
        val a = android.location.Geocoder(this, java.util.Locale("es")).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return ""
        (a.locality ?: a.subAdminArea ?: a.adminArea ?: "").take(24)
    }.getOrDefault("")

    // DISP chose the temperature: the location is asked for once (Android's own dialog), then the weather comes
    private fun wantWeather() {
        if (located()) { if (wx == null) main.post(weather); return }
        if (store.askedLocation) return
        store.askedLocation = true
        toast("DISP · para la temperatura de donde estés, Deck 90 pide tu ubicación aproximada")
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            val hold = pendingLoc; pendingLoc = null
            if (located()) { main.post(weather); if (hold != null) loc(hold) }
            else toast("Sin la ubicación no hay radios locales: se activa en Ajustes → Apps → Deck 90 → Permisos")
            return
        }
        if (requestCode != 2) return
        if (located()) main.post(weather) else toast("Sin la ubicación no hay temperatura: se activa en Ajustes → Apps → Deck 90 → Permisos")
    }

    // a station from the world search: played as a tuned station (not a memory)
    private fun tuneFound(o: JSONObject) {
        val c = controller ?: return
        val name = o.optString("name").trim().take(80); val url = o.optString("url")
        if (name.isEmpty() || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return
        store.tuned = Station(name, "", name.take(60), o.optString("cc").take(2), url)
        store.onTuned = true; Now.song = ""; Now.error = ""; power = true
        scanning = false; main.removeCallbacks(scan)
        store.tunedItem()?.let { c.setMediaItem(it); c.prepare(); c.play() }
        refresh()
    }

    // what the page may ask: called on the page's own thread
    private inner class Bridge {
        @JavascriptInterface fun state(): String = state
        @JavascriptInterface fun levels(): String = Levels.now()
        @JavascriptInterface fun spectrum(): String = Levels.spectrumNow()
        @JavascriptInterface fun key(k: String, hold: Boolean) { main.post { this@MainActivity.key(k, hold) } }
        @JavascriptInterface fun version(): String = BuildConfigInfo.version
        // the answer comes back as faceFound(list), or faceFound(null) when Radio Browser didn't answer
        @JavascriptInterface fun search(q: String, cc: String) {
            Thread {
                val json = runCatching { JSONArray(RadioBrowser.search(q, cc).map { it.toJson() }).toString() }.getOrDefault("null")
                main.post { web.evaluateJavascript("window.faceFound&&faceFound($json)", null) }
            }.start()
        }
        @JavascriptInterface fun tune(json: String) { runCatching { JSONObject(json) }.getOrNull()?.let { o -> main.post { tuneFound(o) } } }
    }
}
