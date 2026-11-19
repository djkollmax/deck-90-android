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
    private var flash = ""; private var flashUntil = 0L              // "VOL 40" or "ATT" on the display, 2 s
    @Volatile private var wx: JSONObject? = null                     // the weather for DISP TEMPERATURA
    // USB: Android's folder picker (a pendrive plugged in by OTG shows there too); the folder then read
    private val usbPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { toast("USB · no elegiste ninguna carpeta: MODE apretado para elegirla"); return@registerForActivityResult }
        Usb.pick(this, uri); usbRead(play = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Usb.load(this)
        Jf.load(this)
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
        androidx.core.content.ContextCompat.registerReceiver(this, usbAnswer, android.content.IntentFilter(usbAsk), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if (store.mode == "cd") main.post(cdPoll)
    }

    override fun onStop() {
        main.removeCallbacks(ticker); main.removeCallbacks(weather); main.removeCallbacks(cdPoll)
        runCatching { unregisterReceiver(usbAnswer) }
        controller?.release(); controller = null
        super.onStop()
    }

    // what the face shows, made on the main thread (the page reads it from its own)
    private var seen = -1
    private val ticker = object : Runnable {
        override fun run() { if (Now.version != seen || flashUntil > 0 || Now.greetUntil > 0 || Sleep.at != 0L) refresh(); main.postDelayed(this, 250) }
    }

    private fun refresh() {
        seen = Now.version
        val c = controller
        val tuned = store.onTuned
        val band = store.band; val slot = if (tuned) 0 else store.slot
        val st = store.current()
        val playing = c?.isPlaying == true
        if (playing) Now.awake()
        val buffering = c?.playbackState == Player.STATE_BUFFERING && c.playWhenReady
        val now = System.currentTimeMillis()
        if (flashUntil in 1 until now) { flash = ""; flashUntil = 0 }
        val usb = store.mode == "usb"
        state = JSONObject()
            .put("mode", store.mode).put("usb", if (usb) usbState(c) else JSONObject.NULL).put("cd", if (store.mode == "cd") cdState(c) else JSONObject.NULL)
            .put("jf", if (store.mode == "server") jfState(c) else JSONObject.NULL)
            .put("band", Bands.label(band)).put("slot", slot).put("freq", st?.freq ?: "").put("tuned", tuned)
            .put("station", st?.name ?: "").put("song", Now.song).put("cc", st?.cc ?: "")
            .put("playing", playing).put("buffering", buffering).put("error", if (playing) "" else Now.error)
            .put("mono", band == "AM" && !tuned).put("quality", if (store.mode == "cd" && Cd.status == "audio" && Cd.parse(c?.currentMediaItem?.mediaId) != null) "PCM 16/44.1"
                else if (store.mode == "server") Jf.quality(Jf.song(Jf.parse(c?.currentMediaItem?.mediaId) ?: "")) else quality(c))
            .put("memo", now < memo).put("scan", scanning).put("power", Now.power).put("greet", Now.greeting(now)).put("flash", flash).put("sleep", Sleep.left(now))
            .put("memories", JSONArray(if (store.mode == "server") jfQueue(c).take(Bands.SLOTS).map { it.name } else if (store.mode == "cd") Cd.numbers().take(Bands.SLOTS).map { Cd.title(it) } else if (usb) Usb.discs.getOrNull(store.usbDisc - 1)?.songs?.take(Bands.SLOTS)?.map { Usb.songName(it.name) } ?: emptyList<String>()
                else store.memories(band).map { it?.name ?: "" }))
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
        if (codec == "FLAC" || f.sampleMimeType == MimeTypes.AUDIO_RAW || f.sampleMimeType == MimeTypes.AUDIO_ALAC) {   // lossless: bits and kHz
            val bits = when (f.pcmEncoding) { androidx.media3.common.C.ENCODING_PCM_24BIT -> 24; androidx.media3.common.C.ENCODING_PCM_32BIT, androidx.media3.common.C.ENCODING_PCM_FLOAT -> 32; androidx.media3.common.C.ENCODING_PCM_16BIT -> 16; else -> 0 }
            val khz = if (f.sampleRate > 0) (if (f.sampleRate % 1000 == 0) (f.sampleRate / 1000).toString() else "%.1f".format(java.util.Locale.ROOT, f.sampleRate / 1000.0)) else ""
            val name = if (codec.isNotEmpty()) codec else if (f.sampleMimeType == MimeTypes.AUDIO_ALAC) "ALAC" else "WAV"
            return listOf(name, if (bits > 0 && khz.isNotEmpty()) "$bits/$khz" else khz).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return listOf(codec, if (kbps > 0) kbps.toString() else "").filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun tune(band: String, slot: Int) {
        val c = controller ?: return
        if (store.station(band, slot) == null) { toast("MEMORIA $slot VACÍA"); return }
        store.band = band; store.slot = slot; store.onTuned = false; Now.song = ""; Now.error = ""; Now.on()
        radioMode(c)
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
        // off, as a stereo switched off: only PWR, SEL, BAND and the memories (on the face or the big keys) switch it on
        if (!Now.power && !(k == "pwr" && !hold || k == "sel" || k == "band" && !hold || Regex("^(m[1-6]|mem\\d{1,2}|band:.+)$").matches(k))) return
        // MODE: RADIO → USB → CD → RADIO (held: pick the pendrive); the CD's slot on the face: the CD
        val tap = { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); refresh() }
        // MODE: RADIO → USB → CD → SERVER → RADIO
        if (k == "mode") { when { hold && store.mode == "server" -> jfFace("login"); hold -> usbChoose(); store.mode == "usb" -> toCd(); store.mode == "cd" -> toServer(); store.mode == "server" -> toRadio(); else -> toUsb() }; tap(); return }
        if (k == "mode:radio") { toRadio(); tap(); return }
        if (k == "slot" && store.mode != "cd") { toCd(); tap(); return }
        if (store.mode == "usb" && Now.power && usbKey(k, hold, c)) { tap(); return }
        if (store.mode == "cd" && Now.power && cdKey(k, hold, c)) { tap(); return }
        if (store.mode == "server" && Now.power && serverKey(k, hold, c)) { tap(); return }
        when {
            k == "pwr" && hold -> {
                val m = Sleep.step()
                flash = if (m > 0) "SLEEP $m" else "SLEEP OFF"; flashUntil = System.currentTimeMillis() + 2000
                toast(if (m > 0) "SLEEP · la radio se apaga sola en $m minutos, a las " +
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(Sleep.at)) +
                    " (PWR apretado otra vez: más tiempo o sin apagado)"
                    else "SLEEP · sin apagado automático")
            }
            k == "pwr" && Now.power -> { Sleep.cancel(); c?.pause(); Now.off("GOOD BYE") }
            k == "pwr" || !Now.power && (k == "band" || k == "sel") -> {   // on (BAND and SEL too): where it was, with HELLO
                Now.on()
                when { c != null && c.mediaItemCount > 0 -> { c.prepare(); c.play() }; store.mode == "usb" -> playUsb(store.usbDisc, store.usbTrack, store.usbPos); store.mode == "cd" -> toCd(); store.mode == "server" -> toServer(); else -> tune(store.band, store.slot) }
            }
            k == "sel" -> when {
                c == null -> {}
                c.isPlaying -> { c.pause(); toast("RADIO · pausa") }
                c.mediaItemCount > 0 -> { Now.on(); c.prepare(); c.play() }
                store.onTuned -> store.tunedItem()?.let { Now.on(); c.setMediaItem(it); c.prepare(); c.play() }
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
            k == "eject" -> toast("RADIO · MODE pasa a USB (un pendrive) y al CD (una lectora por USB)")
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

    // ═══ USB: a pendrive or a folder (Usb.kt), its folders as discs. MODE goes from the radio to it and back; MODE held
    // picks another folder. On USB: SEL play/pause, ◀◀ ▶▶ the songs, 1–6 a song (held 7–12), BAND the next disc (held:
    // the one before), SCAN RAND, LOC REP (the disc, the song, off), EJECT reads the folder again
    private fun radioMode(c: MediaController) {
        store.mode = "radio"; c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF
    }
    private fun toRadio() {
        controller?.let { c -> if (Usb.parse(c.currentMediaItem?.mediaId) != null) store.usbPos = c.currentPosition
            if (Jf.parse(c.currentMediaItem?.mediaId) != null) { store.jfIndex = c.currentMediaItemIndex; store.jfPos = c.currentPosition } }
        if (store.onTuned) store.tunedItem()?.let { item -> controller?.let { c -> radioMode(c); Now.on(); c.setMediaItem(item); c.prepare(); c.play() } }
        else tune(store.band, store.slot)
        toast("MODE · RADIO")
    }
    private fun toUsb() {
        when {
            Usb.root.isEmpty() -> { controller?.pause(); store.mode = "usb"; toast("MODE · USB · para elegir el pendrive (o una carpeta), MODE apretado. MODE otra vez: CD") }
            Usb.scanning -> toast("USB · leyendo la carpeta…")
            Usb.discs.isEmpty() -> usbRead(play = true)
            else -> { playUsb(store.usbDisc, store.usbTrack, store.usbPos); toast("MODE · USB · " + Usb.rootName.ifEmpty { "MÚSICA" } + " · ${Usb.discs.size} " + if (Usb.discs.size == 1) "carpeta" else "carpetas") }
        }
    }
    private fun usbChoose() {
        toast("USB · elegí el pendrive (o una carpeta con música) y tocá USAR ESTA CARPETA")
        runCatching { usbPicker.launch(null) }.onFailure { toast("USB · este celular no deja elegir carpetas") }
    }
    // the folder read again; then, if asked, its first disc (or the one it was on) plays
    private fun usbRead(play: Boolean) {
        if (Usb.scanning) return
        toast("USB · leyendo " + (Usb.rootName.ifEmpty { "la carpeta" }) + "…")
        Thread {
            val ok = Usb.scan(this)
            main.post {
                if (!ok) { toast("USB · no puedo leer la carpeta: ¿está enchufado el pendrive? MODE apretado para elegir otra"); store.mode = "usb"; refresh(); return@post }
                if (Usb.discs.isEmpty()) { toast("USB · no encontré música en " + Usb.rootName); store.mode = "usb"; refresh(); return@post }
                toast("USB · " + Usb.rootName + " · ${Usb.discs.size} " + (if (Usb.discs.size == 1) "carpeta" else "carpetas") + " con música")
                if (play) playUsb(store.usbDisc.coerceAtMost(Usb.discs.size), 1, 0)
                refresh()
            }
        }.start()
    }
    private fun playUsb(d: Int, t: Int, pos: Long) {
        val c = controller ?: return
        val disc = d.coerceIn(1, maxOf(1, Usb.discs.size)); val (items, at) = Usb.playlist(disc, t.coerceAtLeast(1))
        store.mode = "usb"
        if (items.isEmpty()) { refresh(); return }
        scanning = false; main.removeCallbacks(scan); Now.on(); Now.error = ""; Now.song = ""
        c.shuffleModeEnabled = store.usbRand; c.repeatMode = arrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[store.usbRep]
        c.setMediaItems(items, at.coerceIn(0, items.size - 1), if (at < items.size && t >= 1) pos else 0)
        c.prepare(); c.play(); refresh()
    }
    private fun usbKey(k: String, hold: Boolean, c: MediaController?): Boolean {
        val here = Usb.parse(c?.currentMediaItem?.mediaId)
        val disc = here?.first ?: store.usbDisc
        val songs = Usb.discs.getOrNull(disc - 1)?.songs?.size ?: 0
        val track = { n: Int -> if (n in 1..songs) { if (here != null && c != null) { c.seekTo(n - 1, 0); c.play() } else playUsb(disc, n, 0); toast("TRACK · " + n.toString().padStart(2, '0')) } else toast("La carpeta tiene $songs temas") }
        when {
            Usb.discs.isEmpty() && k != "eject" && k != "slot" && k != "pwr" && k.let { it == "sel" || it == "next" || it == "prev" || it.startsWith("m") || it == "band" } -> {
                if (Usb.root.isEmpty()) usbChoose() else usbRead(play = true); return true }
            k == "sel" -> when { c == null -> {}; c.isPlaying -> c.pause(); here != null -> c.play(); else -> playUsb(store.usbDisc, store.usbTrack, store.usbPos) }
            k == "next" -> if (here != null && c != null) { if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekTo(0, 0); c.play() } else playUsb(disc, 1, 0)
            k == "prev" -> if (here != null && c != null) { c.seekToPrevious(); c.play() } else playUsb(disc, 1, 0)
            Regex("^m[1-6]$").matches(k) -> track(k[1].digitToInt() + if (hold) 6 else 0)
            Regex("^mem(\\d{1,3})$").matches(k) -> track(k.removePrefix("mem").toInt())
            k == "band" || k == "discnext" || k == "discprev" -> {
                val n = Usb.discs.size; val to = if (hold || k == "discprev") (disc - 2 + n) % n + 1 else disc % n + 1
                playUsb(to, 1, 0); toast("DISC · $to/$n · " + Usb.discs[to - 1].name)
            }
            Regex("^disc(\\d{1,4})$").matches(k) -> k.removePrefix("disc").toInt().takeIf { it in 1..Usb.discs.size }?.let { playUsb(it, 1, 0); toast("DISC · $it/${Usb.discs.size} · " + Usb.discs[it - 1].name) }
            k == "scan" -> { store.usbRand = !store.usbRand; c?.shuffleModeEnabled = store.usbRand; toast(if (store.usbRand) "RAND · los temas de la carpeta al azar" else "RAND · en orden") }
            k == "loc" -> {
                store.usbRep = (store.usbRep + 1) % 3
                c?.repeatMode = arrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[store.usbRep]
                toast(arrayOf("REP · sin repetir", "REP · repite la carpeta", "REP · repite el tema")[store.usbRep])
            }
            k == "eject" || k == "slot" -> usbRead(play = false)
            k.startsWith("keep") -> toast("USB · los números guardan radios: MODE para volver a la radio")
            else -> return false                                     // the rest as on the radio (VOL, ATT, PWR, the weather)
        }
        return true
    }
    private fun usbState(c: MediaController?): JSONObject {
        val here = Usb.parse(c?.currentMediaItem?.mediaId)
        val d = here?.first ?: store.usbDisc; val t = here?.second ?: store.usbTrack
        val disc = Usb.discs.getOrNull(d - 1)
        val m = if (here != null) c?.mediaMetadata else null
        val file = disc?.songs?.getOrNull(t - 1)?.name.orEmpty()
        val len = c?.duration?.takeIf { here != null && it > 0 } ?: 0L
        return JSONObject().put("root", Usb.rootName).put("picked", Usb.root.isNotEmpty()).put("reading", Usb.scanning)
            .put("discs", Usb.discs.size).put("disc", d).put("discName", disc?.name ?: "").put("n", t).put("total", disc?.songs?.size ?: 0)
            .put("title", m?.title?.toString()?.trim()?.ifEmpty { null } ?: Usb.songName(file))
            .put("artist", m?.artist?.toString()?.trim()?.takeIf { it != disc?.name } ?: "").put("album", m?.albumTitle?.toString()?.trim()?.takeIf { it != disc?.name } ?: "")
            .put("len", len / 1000.0).put("pos", (if (here != null) c?.currentPosition ?: 0L else store.usbPos) / 1000.0)
            .put("rand", store.usbRand).put("rep", store.usbRep)
    }
    // ═══ CD: a disc in a USB drive (Cd.kt). The person allows the drive once (Android asks); while the CD is chosen
    // and no disc plays, the drive is asked every few seconds, and a disc put in plays from its first track, as in a
    // car. On the CD: SEL, ◀◀ ▶▶, 1–6 (held 7–12) its tracks, SCAN RAND, LOC REP, EJECT opens the drive, BAND the radio
    private val usbAsk = "com.djkollmax.deck90.CD_PERMISSION"
    private var asked = false
    private val usbAnswer = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: android.content.Intent) { if (i.action == usbAsk) { asked = false; cdLook(play = true) } }
    }
    private var cdBusy = false
    private fun cdLook(play: Boolean) {
        if (cdBusy) return
        cdBusy = true
        val was = Cd.status
        Thread {
            val now = Cd.refresh(this)
            main.post {
                cdBusy = false
                if (now == "perm" && !asked) {
                    asked = true
                    val um = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                    CdDrive.find(um)?.let { dev ->
                        toast("CD · Android pregunta si Deck 90 puede usar la lectora: tocá ACEPTAR")
                        um.requestPermission(dev, android.app.PendingIntent.getBroadcast(this, 3, android.content.Intent(usbAsk).setPackage(packageName),
                            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
                val ready = now == "audio" || now == "files"
                if (ready && (play || was != now) && store.mode == "cd" && Cd.parse(controller?.currentMediaItem?.mediaId) == null) playCd(Cd.numbers().first())
                refresh()
            }
        }.start()
    }
    private val cdPoll = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (store.mode != "cd") return
            if (!Cd.resting() && (Cd.status != "audio" && Cd.status != "files" || Cd.parse(controller?.currentMediaItem?.mediaId) == null && controller?.isPlaying != true)) cdLook(play = false)
            main.postDelayed(this, 3000)
        }
    }
    private fun toCd() {
        controller?.let { c -> if (Usb.parse(c.currentMediaItem?.mediaId) != null) store.usbPos = c.currentPosition; c.pause() }
        store.mode = "cd"; Now.error = ""
        toast("MODE · CD" + if (Cd.count() > 0) "" else " · conectá la lectora por USB (OTG) y poné un disco")
        if (Cd.count() > 0) playCd(store.cdTrack.takeIf { it in Cd.numbers() } ?: Cd.numbers().first()) else cdLook(play = true)
        main.post(cdPoll)
    }
    private fun playCd(n: Int) {
        val c = controller ?: return
        val (items, at) = Cd.playlist(n)
        store.mode = "cd"
        if (items.isEmpty()) { refresh(); return }
        scanning = false; main.removeCallbacks(scan); Now.on(); Now.error = ""; Now.song = ""
        c.shuffleModeEnabled = store.usbRand; c.repeatMode = arrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[store.usbRep]
        c.setMediaItems(items, at, 0); c.prepare(); c.play(); refresh()
    }
    private fun cdKey(k: String, hold: Boolean, c: MediaController?): Boolean {
        val here = Cd.parse(c?.currentMediaItem?.mediaId)
        val all = Cd.numbers()
        val track = { n: Int -> if (n in all) { if (here != null && c != null) { c.seekTo(all.indexOf(n), 0); c.play() } else playCd(n); toast("TRACK · " + n.toString().padStart(2, '0')) }
            else toast(if (all.isEmpty()) "CD · no hay un disco" else "El disco tiene ${all.size} " + if (Cd.status == "files") "archivos" else "pistas") }
        when {
            k == "eject" -> { c?.pause(); if (Cd.eject()) toast("EJECT · la lectora se abre") else toast("CD · no hay lectora"); main.post(cdPoll) }
            all.isEmpty() && k in setOf("sel", "next", "prev", "slot") -> { cdLook(play = true); toast(when (Cd.status) { "none" -> "CD · conectá la lectora por USB (OTG)"; "nodisc" -> "CD · poné un disco"; "perm" -> "CD · aceptá el permiso de Android para la lectora"; "empty" -> "CD · el disco no tiene música"; "error" -> "CD · no pude leer la lectora: desenchufala y volvé a enchufarla"; else -> "CD · leyendo…" }) }
            k == "sel" || k == "slot" -> when { c == null -> {}; c.isPlaying -> c.pause(); here != null -> c.play(); else -> playCd(store.cdTrack.takeIf { it in all } ?: all.first()) }
            k == "next" -> if (here != null && c != null) { if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekTo(0, 0); c.play() } else playCd(all.first())
            k == "prev" -> if (here != null && c != null) { c.seekToPrevious(); c.play() } else playCd(all.first())
            Regex("^m[1-6]$").matches(k) -> track(k[1].digitToInt() + if (hold) 6 else 0)
            Regex("^mem(\\d{1,3})$").matches(k) -> track(k.removePrefix("mem").toInt())
            k == "scan" -> { store.usbRand = !store.usbRand; c?.shuffleModeEnabled = store.usbRand; toast(if (store.usbRand) "RAND · las pistas al azar" else "RAND · en orden") }
            k == "loc" -> {
                store.usbRep = (store.usbRep + 1) % 3
                c?.repeatMode = arrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[store.usbRep]
                toast(arrayOf("REP · sin repetir", "REP · repite el disco", "REP · repite la pista")[store.usbRep])
            }
            k == "band" -> toRadio()
            k.startsWith("keep") -> toast("CD · los números guardan radios: MODE para volver a la radio")
            else -> return false
        }
        return true
    }
    private fun cdState(c: MediaController?): JSONObject {
        val here = Cd.parse(c?.currentMediaItem?.mediaId)
        val n = here ?: store.cdTrack
        val len = c?.duration?.takeIf { here != null && it > 0 } ?: 0L
        return JSONObject().put("status", Cd.status).put("files", Cd.status == "files").put("n", if (Cd.count() > 0) n else 0).put("total", Cd.count())
            .put("title", if (Cd.count() > 0) Cd.title(n) else "").put("artist", if (Cd.count() > 0) Cd.artist(n) else "")
            .put("album", Cd.names?.album ?: "").put("year", Cd.names?.year ?: "")
            .put("len", len / 1000.0).put("pos", (if (here != null) c?.currentPosition ?: 0L else 0L) / 1000.0)
            .put("rand", store.usbRand).put("rep", store.usbRep)
    }

    // ═══ SERVER: the person's Jellyfin music (Jellyfin.kt). MODE after the CD; not signed in, the face asks for the
    // server, user and password (MODE held asks again). On SERVER: SEL, ◀◀ ▶▶, 1–6 (held 7–12) the songs playing,
    // BAND the next album (held: the one before), SCAN RAND, LOC REP, EJECT (or MÚSICA on the big keys) the lists
    private fun jfFace(what: String, data: String = "null") = main.post { web.evaluateJavascript("window.faceJf&&faceJf(${JSONObject.quote(what)},$data)", null) }
    private fun toServer() {
        controller?.let { c -> if (Usb.parse(c.currentMediaItem?.mediaId) != null) store.usbPos = c.currentPosition }
        store.mode = "server"; Now.error = ""
        if (!Jf.signedIn) { controller?.pause(); toast("MODE · SERVER · tu música de Jellyfin: poné el servidor, el usuario y la contraseña"); jfFace("login"); refresh(); return }
        Thread { Jf.read() }.start()
        val q = Jf.queueFrom(store.jfQueue)
        if (q.isNotEmpty()) { playServer(q, store.jfIndex, store.jfPos); toast("MODE · SERVER · " + Jf.name) }
        else { controller?.pause(); toast("MODE · SERVER · elegí qué escuchar"); jfFace("browse") }
        refresh()
    }
    private fun jfQueue(c: MediaController?): List<JfSong> {
        if (c == null || Jf.parse(c.currentMediaItem?.mediaId) == null) return Jf.queueFrom(store.jfQueue)
        return (0 until c.mediaItemCount).mapNotNull { Jf.parse(c.getMediaItemAt(it).mediaId)?.let { id -> Jf.song(id) } }
    }
    private fun playServer(songs: List<JfSong>, at: Int, pos: Long = 0) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val i = at.coerceIn(0, songs.size - 1)
        store.mode = "server"; store.jfQueue = Jf.queueJson(songs); store.jfIndex = i; store.jfPos = pos
        scanning = false; main.removeCallbacks(scan); Now.on(); Now.error = ""; Now.song = ""
        c.shuffleModeEnabled = store.usbRand; c.repeatMode = arrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[store.usbRep]
        c.setMediaItems(songs.map { Jf.item(it) }, i, pos); c.prepare(); c.play(); refresh()
    }
    // the next album (or the one before) in the library's order, from its first song
    private fun jfAlbumStep(back: Boolean) {
        val now = jfQueue(controller).getOrNull(controller?.currentMediaItemIndex ?: 0)?.albumId ?: ""
        Thread {
            Jf.read()
            val all = Jf.albums
            if (all.isEmpty()) return@Thread
            val i = all.indexOfFirst { it.id == now }
            val next = all[if (i < 0) 0 else (i + (if (back) -1 else 1) + all.size) % all.size]
            val songs = Jf.songsOf(next.id)
            main.post { if (songs.isNotEmpty()) { playServer(songs, 0); toast("ALBUM · " + next.name + (if (next.artist.isNotEmpty()) " · " + next.artist else "")) } }
        }.start()
    }
    private fun serverKey(k: String, hold: Boolean, c: MediaController?): Boolean {
        val here = Jf.parse(c?.currentMediaItem?.mediaId) != null
        val q = jfQueue(c)
        when {
            !Jf.signedIn && k in setOf("sel", "next", "prev", "band", "eject", "slot") -> jfFace("login")
            k == "eject" || k == "slot" || k == "jfbrowse" -> jfFace("browse")
            k == "sel" -> when { c == null -> {}; c.isPlaying -> c.pause(); here -> c.play(); q.isNotEmpty() -> playServer(q, store.jfIndex, store.jfPos); else -> jfFace("browse") }
            k == "next" -> if (here && c != null) { if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekTo(0, 0); c.play() } else if (q.isNotEmpty()) playServer(q, 0)
            k == "prev" -> if (here && c != null) { c.seekToPrevious(); c.play() } else if (q.isNotEmpty()) playServer(q, 0)
            Regex("^m[1-6]$").matches(k) || Regex("^mem(\\d{1,3})$").matches(k) -> {
                val n = if (k.startsWith("mem")) k.removePrefix("mem").toInt() else k[1].digitToInt() + if (hold) 6 else 0
                if (n in 1..q.size) { if (here && c != null) { c.seekTo(n - 1, 0); c.play() } else playServer(q, n - 1); toast("TRACK · " + n.toString().padStart(2, '0') + " · " + q[n - 1].name) }
                else toast(if (q.isEmpty()) "SERVER · elegí un disco con MÚSICA" else "Suenan ${q.size} temas")
            }
            k == "band" -> jfAlbumStep(hold)
            k == "scan" -> { store.usbRand = !store.usbRand; c?.shuffleModeEnabled = store.usbRand; toast(if (store.usbRand) "RAND · los temas al azar" else "RAND · en orden") }
            k == "loc" -> {
                store.usbRep = (store.usbRep + 1) % 3
                c?.repeatMode = arrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)[store.usbRep]
                toast(arrayOf("REP · sin repetir", "REP · repite el disco", "REP · repite el tema")[store.usbRep])
            }
            k.startsWith("keep") -> toast("SERVER · los números guardan radios: MODE para volver a la radio")
            else -> return false
        }
        return true
    }
    private fun jfState(c: MediaController?): JSONObject {
        val q = jfQueue(c); val here = Jf.parse(c?.currentMediaItem?.mediaId) != null
        val i = if (here) c!!.currentMediaItemIndex else store.jfIndex
        val s = q.getOrNull(i)
        val len = c?.duration?.takeIf { here && it > 0 } ?: ((s?.len ?: 0) * 1000L)
        return JSONObject().put("signedIn", Jf.signedIn).put("server", Jf.server).put("name", Jf.name).put("reading", Jf.reading)
            .put("albums", Jf.albums.size).put("artists", Jf.artists.size).put("error", Jf.error)
            .put("n", if (s != null) i + 1 else 0).put("total", q.size).put("track", s?.n ?: 0)
            .put("title", s?.name ?: "").put("artist", s?.artist ?: "").put("album", s?.album ?: "").put("albumId", s?.albumId ?: "")
            .put("len", len / 1000.0).put("pos", (if (here) c!!.currentPosition else store.jfPos) / 1000.0)
            .put("rand", store.usbRand).put("rep", store.usbRep)
    }
    // the face's lists (its MÚSICA window): what's asked, answered later through faceJfList(req, …)
    private fun jfList(req: Int, kind: String, id: String, text: String) = Thread {
        val o = JSONObject().put("kind", kind).put("id", id)
        val album = { a: JfAlbum -> JSONObject().put("t", "album").put("id", a.id).put("name", a.name).put("sub", listOf(a.artist, if (a.year > 0) a.year.toString() else "").filter { it.isNotEmpty() }.joinToString(" · ")) }
        val song = { x: JfSong, i: Int -> JSONObject().put("t", "song").put("id", x.id).put("i", i).put("name", x.name).put("sub", listOf(x.artist, x.album).filter { it.isNotEmpty() }.joinToString(" · "))
            .put("n", x.n).put("len", x.len).put("q", Jf.quality(x)) }
        try {
            if (kind != "home" && kind != "search") Jf.read()
            val items: List<JSONObject> = when (kind) {
                "home" -> { Jf.read(); emptyList() }
                "albums" -> Jf.albums.map(album)
                "artists" -> Jf.artists.map { JSONObject().put("t", "artist").put("id", it.id).put("name", it.name) }
                "artist" -> Jf.albumsOf(id).map(album).also { o.put("title", Jf.artists.firstOrNull { a -> a.id == id }?.name ?: "") }
                "album" -> Jf.songsOf(id).mapIndexed { i, x -> song(x, i) }.also { o.put("title", Jf.albums.firstOrNull { a -> a.id == id }?.name ?: Jf.songsOf(id).firstOrNull()?.album ?: "") }
                "recent" -> Jf.recent().map(album)
                "queue" -> jfQueueNow().mapIndexed { i, x -> song(x, i) }
                "search" -> { Jf.read(); val (ar, al, so) = Jf.search(text)
                    ar.map { JSONObject().put("t", "artist").put("id", it.id).put("name", it.name) } + al.map(album) + so.mapIndexed { i, x -> song(x, i).put("t", "found") } }
                else -> emptyList()
            }
            o.put("items", JSONArray(items)).put("albums", Jf.albums.size).put("artists", Jf.artists.size).put("error", Jf.error)
        } catch (e: Exception) { o.put("items", JSONArray()).put("error", e.message ?: "error") }
        main.post { web.evaluateJavascript("window.faceJfList&&faceJfList($req,$o)", null) }
    }.start()
    private fun jfQueueNow(): List<JfSong> = jfQueue(controller)
    // what's touched in the lists plays: an album from a song, a found song alone with the others found, AL AZAR
    private fun jfPlay(kind: String, id: String, index: Int) = Thread {
        val songs: List<JfSong>; var at = index
        when (kind) {
            "album" -> songs = Jf.songsOf(id)
            "shuffle" -> { songs = Jf.shuffle(); at = 0 }
            "artist" -> { songs = Jf.albumsOf(id).flatMap { Jf.songsOf(it.id) }.take(500); at = 0 }
            "found" -> { val s = Jf.song(id); songs = if (s != null && s.albumId.isNotEmpty()) Jf.songsOf(s.albumId) else listOfNotNull(s); at = songs.indexOfFirst { it.id == id }.coerceAtLeast(0) }
            "queue" -> { main.post { controller?.let { c -> if (index < c.mediaItemCount) { c.seekTo(index, 0); c.play() } } }; return@Thread }
            else -> return@Thread
        }
        main.post { if (songs.isEmpty()) toast("SERVER · " + (Jf.error.ifEmpty { "no hay temas" })) else playServer(songs, at) }
    }.start()

    // the face's list of discs (the pad's CARPETAS)
    private fun discsJson(): String = JSONArray(Usb.discs.mapIndexed { i, d -> JSONObject().put("d", i + 1).put("name", d.name).put("songs", d.songs.size) }).toString()

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
        store.onTuned = true; Now.song = ""; Now.error = ""; Now.on()
        radioMode(c)
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
        @JavascriptInterface fun discs(): String = discsJson()
        // SERVER (Jellyfin): sign in (the password goes to the server and is forgotten: the token is kept), the lists, play
        @JavascriptInterface fun jfSignIn(server: String, user: String, pw: String) {
            Thread { val err = Jf.signIn(server, user, pw); if (err.isEmpty()) Jf.read(force = true)
                jfFace("signed", JSONObject().put("error", err).put("name", Jf.name).put("albums", Jf.albums.size).toString())
                main.post { refresh() } }.start()
        }
        @JavascriptInterface fun jfSignOut() { Thread { Jf.signOut(); main.post { controller?.let { c -> if (Jf.parse(c.currentMediaItem?.mediaId) != null) c.stop() }; store.jfQueue = "[]"; refresh() } }.start() }
        @JavascriptInterface fun jfList(req: Int, kind: String, id: String, text: String) { this@MainActivity.jfList(req, kind, id, text) }
        @JavascriptInterface fun jfPlay(kind: String, id: String, index: Int) { this@MainActivity.jfPlay(kind, id, index) }
        @JavascriptInterface fun jfServer(): String = JSONObject().put("server", Jf.server.ifEmpty { "" }).put("name", Jf.name).put("signedIn", Jf.signedIn).toString()
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
