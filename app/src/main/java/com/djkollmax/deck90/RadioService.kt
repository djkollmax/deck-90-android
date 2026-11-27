package com.djkollmax.deck90

import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import java.io.IOException

// ═══════════════════════════════════════
//  THE RADIO: one player for the phone and for Android Auto. Its browse tree is the bands and their memories; a
//  memory plays with the rest of its band as the list (so ◀◀ ▶▶ in the car change stations). A memory's address is
//  found when it's played (Resolver): the one it came with, the one that played last, or Radio Browser's best.
//  The song a station sends (ICY) goes to the session as the title, the station's name as the artist. USB (Usb.kt):
//  a pendrive's folders as discs, a song played with the rest of its disc.
// ═══════════════════════════════════════
@UnstableApi
class RadioService : MediaLibraryService() {
    // LOCALES' lookups and the server's lists: 0.1.11, three at once (one slow server no longer held the car's other lists)
    private val background = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))

    private lateinit var store: Store
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var jfSkips = 0
    private var retried = ""                                         // the station already retried after an error
    // 0.1.11: a radio that drops (the car between antennas, Wi-Fi to mobile) is tuned again by itself, longer and longer
    // apart, as the PC's RECONECTANDO; playing well for a while, the count starts over
    private var radioTries = 0
    private var playingSince = 0L
    private val main = Handler(android.os.Looper.getMainLooper())
    private val RETRY_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000, 60_000)
    // 0.1.12: then once a minute, for half an hour (it gave up after two minutes: a long cut with the screen off was the end)
    private val MAX_TRIES = RETRY_MS.size + 30
    private var nowId: String? = null
    private var nowIndex = -1
    // 0.1.12: while it waits to try again the player holds nothing, so with the screen off the tablet slept (the
    // retry never came) and turned its Wi-Fi off: the service keeps the CPU and the Wi-Fi awake until it plays again
    private var cpuHold: android.os.PowerManager.WakeLock? = null
    private var wifiHold: android.net.wifi.WifiManager.WifiLock? = null
    // and the service stays in the foreground: with the player stopped by the error Media3 let it go to the background,
    // where Android freezes or closes an app with the screen off (onUpdateNotification below)
    @Volatile private var reconnecting = false
    private fun holdOn(ms: Long) {
        reconnecting = true
        runCatching {
            val cpu = cpuHold ?: (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "deck90:reconnect").apply { setReferenceCounted(false) }.also { cpuHold = it }
            cpu.acquire(ms)
            @Suppress("DEPRECATION")
            val wifi = wifiHold ?: (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "deck90:reconnect").apply { setReferenceCounted(false) }.also { wifiHold = it }
            if (!wifi.isHeld) wifi.acquire()
        }
    }
    private fun holdOff() { reconnecting = false; runCatching { cpuHold?.takeIf { it.isHeld }?.release(); wifiHold?.takeIf { it.isHeld }?.release() } }
    private fun isRadio(id: String?) = id == Store.TUNED || Store.parse(id) != null

    // 0.1.12: the radio or the server stopped (a drop, or a station's stream that just ended): the same item again,
    // longer and longer apart (RECONNECTING… on the display), the server's song from where it was. Half a minute
    // playing well starts the count over
    private fun reconnect(id: String, index: Int, ended: Boolean = false) {
        if (playingSince > 0 && System.currentTimeMillis() - playingSince > 30_000) radioTries = 0
        playingSince = 0
        if (radioTries >= MAX_TRIES) { Now.error = if (isRadio(id)) "Sin señal" else "NO SERVER"; Now.changed(); holdOff(); return }
        val wait = if (ended && radioTries == 0) 500L else RETRY_MS[radioTries.coerceAtMost(RETRY_MS.size - 1)]
        radioTries++
        Now.error = "RECONNECT"; Now.changed()
        holdOn(wait + 60_000)
        main.postDelayed({
            val p = player
            if (p.playWhenReady && p.currentMediaItemIndex == index && p.currentMediaItem?.mediaId == id && (p.playerError != null || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED)) {
                if (p.playbackState == Player.STATE_ENDED || isRadio(id)) p.seekToDefaultPosition(index)
                p.prepare(); p.play()
            } else if (!p.isPlaying) holdOff()
        }, wait)
    }

    override fun onCreate() {
        super.onCreate()
        // 0.1.24: a CD's names (and its cover) come after it starts playing: the tracks in the player get them, so the
        // notification, the lock screen and the car say the song and not TRACK 03
        Cd.onNames = { main.post { cdRename(); shown = ""; menus() } }
        BuildConfigInfo.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" }.getOrDefault("0")
        store = Store(this)
        Usb.load(this)
        Jf.load(this)
        Music.load(this)
        val http = DefaultHttpDataSource.Factory().setUserAgent("Deck90-Android/" + BuildConfigInfo.version)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val data = ResolvingDataSource.Factory(CdOrDefault.Factory(DefaultDataSource.Factory(this, http)), Resolver(store))   // the CD's own (Cd.kt)
        player = ExoPlayer.Builder(this, MeterRenderers(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        Sleep.attach(player)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // 0.1.12: a station's stream that ended by itself (its server closed it: some do every so often) went
                // on to the band's next memory, or stopped: it's the same station, tuned again
                val was = nowId; val wasIndex = nowIndex
                nowId = item?.mediaId; nowIndex = player.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && isRadio(was) && wasIndex in 0 until player.mediaItemCount && player.getMediaItemAt(wasIndex).mediaId == was) {
                    nowId = was; nowIndex = wasIndex
                    player.seekToDefaultPosition(wasIndex); player.stop()
                    reconnect(was!!, wasIndex, ended = true)
                    return
                }
                val usb = Usb.parse(item?.mediaId)
                val cd = Cd.parse(item?.mediaId)
                val jf = Jf.parse(item?.mediaId)
                if (jf != null) { store.mode = "server"; store.jfIndex = player.currentMediaItemIndex; store.jfPos = 0; listPlace(true) }
                else if (cd != null) { store.mode = "cd"; store.cdTrack = cd }
                else if (usb != null) { store.mode = "usb"; store.usbDisc = usb.first; store.usbTrack = usb.second; store.usbPos = 0 }
                else if (item?.mediaId == Store.TUNED) { store.mode = "radio"; store.onTuned = true }
                else Store.parse(item?.mediaId)?.let { (b, s) -> store.mode = "radio"; store.band = b; store.slot = s; store.onTuned = false }
                Now.song = ""; Now.error = ""; Now.changed()
            }
            override fun onMediaMetadataChanged(m: MediaMetadata) { Now.changed() }
            // a song from USB (0.1.15: or an MP3 CD's): its own tags once the file is read (the item keeps them: asked once)
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val item = player.currentMediaItem ?: return
                if ((Usb.parse(item.mediaId) == null && !item.mediaId.startsWith("cdf:")) || item.mediaMetadata.extras?.getBoolean("tags") == true) return
                val t = Tags.read(tracks)
                val m = item.mediaMetadata.buildUpon().apply {
                    t?.title?.let { setTitle(it) }; t?.artist?.let { setArtist(it) }; t?.album?.let { setAlbumTitle(it) }
                    t?.art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    setExtras(Bundle().apply { putBoolean("tags", true) })
                }.build()
                runCatching { player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(m).build()) }
            }
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is IcyInfo) { Now.song = (e.title ?: "").trim(); Now.changed() }
                }
            }
            // 0.1.12: the last station of the list (or one tuned from LOCALES) ending by itself: tuned again
            override fun onPlaybackStateChanged(state: Int) {
                val id = player.currentMediaItem?.mediaId
                if (state == Player.STATE_ENDED && player.playWhenReady && isRadio(id)) reconnect(id!!, player.currentMediaItemIndex, ended = true)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) holdOff() }   // stopped by the person: nothing to keep awake
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { jfSkips = 0; playingSince = System.currentTimeMillis(); if (Now.error == "RECONNECT") Now.error = ""; holdOff() }
                if (!isPlaying && Usb.parse(player.currentMediaItem?.mediaId) != null) store.usbPos = player.currentPosition   // the car comes back here
                if (!isPlaying && Jf.parse(player.currentMediaItem?.mediaId) != null) { store.jfIndex = player.currentMediaItemIndex; store.jfPos = player.currentPosition; listPlace(true) }
                Now.changed()
            }
            override fun onPlayerError(error: PlaybackException) {
                // the address stopped answering: forgotten, and the station asked of Radio Browser once more
                val item = player.currentMediaItem ?: return
                if (Usb.parse(item.mediaId) != null) {
                    // 0.1.10: a file the phone can't play (its format, or damaged): the next one, up to three in a row;
                    // anything else is the pendrive or the card gone: NO USB
                    val bad = error.errorCode in setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
                    if (bad && jfSkips < 3 && player.hasNextMediaItem()) { jfSkips++; Now.error = ""; player.seekToNextMediaItem(); player.prepare(); player.play() }
                    else Now.error = "NO USB"
                    Now.changed(); return
                }
                if (item.localConfiguration?.uri?.scheme == "cdda") { Now.error = "NO DISC"; Now.changed(); return }   // the drive or the disc
                if (Jf.parse(item.mediaId)?.let { Tel.mine(it) } == true) {   // 0.1.10: a song on the phone it can't play (or gone): the next one
                    if (jfSkips < 3 && player.hasNextMediaItem()) { jfSkips++; Now.error = ""; player.seekToNextMediaItem(); player.prepare(); player.play() }
                    else Now.error = "NO FILE"
                    Now.changed(); return
                }
                if (Jf.parse(item.mediaId) != null) {
                    // 0.1.10: the server answered but not with the song (a file it can't read or convert): the next one, up
                    // to three in a row; it answered 401: sign in again; it didn't answer at all: NO SERVER
                    val http = generateSequence(error.cause) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    when {
                        // 0.1.15: a song answered 401: signed out only if the server says so for the person too (a song
                        // alone, or a server that doesn't take the token in a song's address, isn't a reason)
                        http?.responseCode == 401 -> { val index = player.currentMediaItemIndex; Thread {
                            val still = Jf.check()
                            main.post {
                                if (still == false) { Now.error = "SIGN IN"; Now.changed() }
                                else if (player.currentMediaItemIndex == index && jfSkips < 3 && player.hasNextMediaItem()) { jfSkips++; Now.error = ""; player.seekToNextMediaItem(); player.prepare(); player.play() }
                                else { Now.error = "NO SERVER"; Now.changed() }
                            }
                        }.start(); return }
                        http != null && jfSkips < 3 && player.hasNextMediaItem() -> { jfSkips++; Now.error = ""; player.seekToNextMediaItem(); player.prepare(); player.play() }
                        // 0.1.12: no answer (the Wi-Fi went, the server rebooting): tried again, the song from where it was
                        Jf.signedIn -> { Thread { Jf.reach() }.start(); reconnect(item.mediaId, player.currentMediaItemIndex); return }   // 0.1.16: or its other address
                        else -> Now.error = "SIGN IN"
                    }
                    Now.changed(); return
                }
                val st = (if (item.mediaId == Store.TUNED) store.tuned else Store.parse(item.mediaId)?.let { (b, s) -> store.station(b, s) }) ?: return
                // it had played well for half a minute: a new drop, not the same one
                if (playingSince > 0 && System.currentTimeMillis() - playingSince > 30_000) { retried = ""; radioTries = 0 }
                if (retried != item.mediaId) { playingSince = 0; retried = item.mediaId; radioTries = 0; store.resolvedSet(st, null); Resolver.fail(st); holdOn(60_000); player.prepare(); player.play(); return }
                reconnect(item.mediaId, player.currentMediaItemIndex)
            }
        })
        session = MediaLibrarySession.Builder(this, player, Library()).build()
        main.post(watch)
    }

    // 0.1.27: every few seconds: the car's menus told again when what's in them changed (a CD or a pendrive in or out,
    // the server signed in, a memory saved on the phone; the menu showed the old ones until it was left and opened
    // again), and where a list is, sent to Jellyfin every 15 s while it plays
    private var shown = ""
    private var ticks = 0
    private val watch = object : Runnable {
        override fun run() {
            menus()
            if (++ticks % 5 == 0) listPlace(false)
            main.postDelayed(this, 3_000)
        }
    }
    private fun menus() {
        val s = session ?: return
        val music = listOf(Usb.discs.size, Usb.rootName, Cd.count(), Cd.status, Cd.id, Cd.names?.album, Cd.art != null, Jf.signedIn, Jf.name, Tel.ready).joinToString("|")
        val radio = Bands.order.joinToString("|") { b -> store.memories(b).joinToString(",") { st -> st?.let { it.name + "/" + it.freq } ?: "" } }
        val now = music + "\n" + radio
        if (now == shown) return
        val was = shown.split("\n"); shown = now
        if (was.getOrNull(0) != music) for (id in listOf("music", "cd", "usb", "jf", "tel")) s.notifyChildrenChanged(id, Int.MAX_VALUE, null)
        if (was.getOrNull(1) != radio) { s.notifyChildrenChanged("radio", Bands.order.size, null); for (b in Bands.order) s.notifyChildrenChanged("band:$b", Bands.SLOTS, null) }
    }

    // 0.1.27: the Jellyfin list playing (Store.jfList: from the car or the phone): which song and where, to Jellyfin,
    // so it goes on from there here, on the phone or on the PC. While it plays, every 15 s; on a pause or a new song, now
    private var listSent = ""
    private fun listPlace(now: Boolean) {
        val list = store.jfList
        if (list.isEmpty() || store.mode != "server") return
        val id = Jf.parse(player.currentMediaItem?.mediaId) ?: return
        if (!player.isPlaying && !now) return
        val t = player.currentPosition / 1000; val i = player.currentMediaItemIndex
        val key = "$list|$i|$id|" + t / 15
        if (key == listSent) return
        listSent = key
        background.submit { Jf.placeSet(list, id, i, t) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // 0.1.12: reconnecting counts as playing for the notification, so the service isn't sent to the background
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || (reconnecting && player.playWhenReady))
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()   // swiped away while silent: gone
    }

    private fun cdRename() {
        if (!::player.isInitialized || player.mediaItemCount == 0 || Cd.status != "audio") return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val items = ids.map { id -> Cd.parse(id)?.let { Cd.item(it) } ?: return }
        runCatching { player.replaceMediaItems(0, items.size, items) }   // the same tracks: it goes on playing
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        holdOff()
        Sleep.attach(null)
        background.shutdownNow()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    // the browse tree. 0.1.27: three tabs, as Android Auto shows them (it shows four at most, and more were lost:
    // FM5, HI-FI, AM, LOCALES and all the music): RADIO (the bands, each with its memories), LOCALES and MÚSICA (USB,
    // CD, SERVER, TEL). Every row has its picture (CarArt), the memories and the albums go as a grid
    private inner class Library : MediaLibrarySession.Callback {
        private val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        private val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        // how the car lays out a folder's rows: its folders (browsable) and what plays in it (playable)
        private fun style(folders: Int = LIST, plays: Int = LIST) = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, folders); putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, plays) }
        private fun folder(id: String, title: String, sub: String? = null, art: Uri? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, extras: Bundle? = null) =
            MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtworkUri(art)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).setExtras(extras).build()).build()
        private fun playable(id: String, title: String, sub: String? = null, art: Uri? = null) =
            MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtworkUri(art)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()).build()
        private fun art(item: MediaItem, uri: Uri?): MediaItem = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setArtworkData(null, null).setArtworkUri(uri).build()).build()

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Deck 90", type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                LibraryParams.Builder().setRecent(params?.isRecent == true).setOffline(params?.isOffline == true).setSuggested(params?.isSuggested == true)
                    .setExtras(style()).build()))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = when {
                parentId == "root" -> listOf(
                    folder("radio", "RADIO", art = CarArt.icon("radio"), type = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, extras = style()),
                    folder("band:${Bands.LOCAL}", "LOCALES", art = CarArt.icon("local"), type = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, extras = style(plays = GRID)),
                    folder("music", "MÚSICA", art = CarArt.icon("music"), extras = style()))
                parentId == "radio" -> Bands.order.map { b ->
                    val st = (1..Bands.SLOTS).mapNotNull { store.station(b, it) }
                    folder("band:$b", Bands.label(b), st.take(4).joinToString(" · ") { it.name } + if (st.size > 4) " · …" else "",
                        CarArt.face(CarArt.bandFace(b, st.size)), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, style(plays = GRID)) }
                parentId == "music" -> music()
                parentId == "jflists" || parentId.startsWith("jfplist:") -> return lists(parentId, params)
                parentId == "jf" || parentId.startsWith("jf") || parentId.startsWith("tel") -> return server(parentId, params)
                parentId == "cd" -> Cd.playlist(1).first.map { art(it, CarArt.cd(Cd.id)) }
                parentId == "usb" -> (1..Usb.discs.size).mapNotNull { d -> Usb.discItem(d)?.let { art(it, CarArt.face(CarArt.Face("USB", word(Usb.discs[d - 1].name), "${Usb.discs[d - 1].songs.size} TEM"))) } }
                parentId.startsWith("usbdisc:") -> parentId.removePrefix("usbdisc:").toIntOrNull()?.let { d -> Usb.playlist(d, 1).first } ?: emptyList()
                parentId == "band:${Bands.LOCAL}" -> return local(params)
                parentId.startsWith("band:") -> parentId.removePrefix("band:").let { b -> (1..Bands.SLOTS).mapNotNull { store.item(b, it) } }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
        }
        private fun word(s: String) = s.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.isNotEmpty() }?.take(5) ?: ""

        // MÚSICA: what there is to play now (the menu is told again when it changes: menus())
        private fun music(): List<MediaItem> {
            val out = ArrayList<MediaItem>()
            if (Usb.discs.isNotEmpty()) out += folder("usb", "USB", Usb.rootName.ifEmpty { "PENDRIVE" } + " · " + Usb.discs.size + (if (Usb.discs.size == 1) " carpeta" else " carpetas"),
                CarArt.icon("usb"), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, style(GRID, LIST))
            if (Cd.count() > 0) out += folder("cd", "CD", (Cd.names?.let { n -> listOf(n.album, n.artist).filter { it.isNotEmpty() }.joinToString(" · ") }?.ifEmpty { null }
                    ?: if (Cd.status == "files") "MP3" else "AUDIO") + " · " + Cd.count() + " temas",
                if (Cd.art != null) CarArt.cd(Cd.id) else CarArt.icon("cd"), MediaMetadata.MEDIA_TYPE_ALBUM, style())
            if (Jf.signedIn) out += folder("jf", "SERVER", "Jellyfin" + if (Jf.name.isNotEmpty()) " · " + Jf.name else "", CarArt.icon("server"), extras = style())
            if (Tel.ready) out += folder("tel", "TEL", "La música del teléfono", CarArt.icon("tel"), extras = style())          // 0.1.10
            if (out.isEmpty()) out += folder("none", "SIN MÚSICA", "Enchufá un pendrive o un CD, o entrá al servidor desde el celular", CarArt.icon("music"))
            return out
        }

        // SERVER in the car (Jellyfin.kt), and TEL (Tel.kt) the same from "tel": AL AZAR, RECIENTES, LISTAS (the
        // server's), DISCOS and ARTISTAS by letter, an artist's albums, an album's songs (those two by their own ids).
        // What needs the server is asked off the main thread
        private fun server(id: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            background.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val tel = id.startsWith("tel"); val p = if (tel) "tel" else "jf"
                val lib: MusicLib = when { tel -> Tel; id.startsWith("jfartist:") -> Music.of(id.removePrefix("jfartist:")); id.startsWith("jfalbum:") -> Music.of(id.removePrefix("jfalbum:")); else -> Jf }
                lib.read()
                val letters = { what: String, names: List<String> -> names.groupBy { Jf.letter(it) }.toSortedMap(compareBy { if (it == "#") "~" else it }).map { (l, a) ->
                    folder("$p$what:$l", l, a.size.toString() + if (what == "albums") (if (a.size == 1) " disco" else " discos") else if (a.size == 1) " artista" else " artistas",
                        CarArt.face(CarArt.Face(if (what == "albums") "DISCOS" else "ARTISTAS", l, a.size.toString())), extras = if (what == "albums") style(GRID) else style()) } }
                val list: List<MediaItem> = when {
                    id == p -> listOfNotNull(
                        playable("${p}shuffle", "AL AZAR", "100 temas", CarArt.icon("shuffle")),
                        folder("${p}recent", "RECIENTES", "Lo último que entró", CarArt.icon("recent"), extras = style(GRID)),
                        if (!tel) folder("jflists", "LISTAS", "Cada una sigue donde la dejaste", CarArt.icon("lists"), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, style()) else null,
                        folder("${p}albums", "DISCOS", lib.albums.size.toString() + " discos", CarArt.icon("albums"), MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, style(GRID)),
                        folder("${p}artists", "ARTISTAS", lib.artists.size.toString() + " artistas", CarArt.icon("artists"), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, style()))
                    id == "${p}recent" -> lib.recent().map { Jf.albumItem(it) }
                    id == "${p}albums" -> letters("albums", lib.albums.map { it.name })
                    id.startsWith("${p}albums:") -> id.removePrefix("${p}albums:").let { l -> lib.albums.filter { Jf.letter(it.name) == l }.map { Jf.albumItem(it) } }
                    id == "${p}artists" -> letters("artists", lib.artists.map { it.name })
                    id.startsWith("${p}artists:") -> id.removePrefix("${p}artists:").let { l -> lib.artists.filter { Jf.letter(it.name) == l }.map { artist(it) } }
                    id.startsWith("jfartist:") -> lib.albumsOf(id.removePrefix("jfartist:")).map { Jf.albumItem(it) }
                    id.startsWith("jfalbum:") -> id.removePrefix("jfalbum:").let { a -> lib.songsOf(a).map { Jf.item(it, "album:$a") } }
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
            }
        private fun artist(a: JfArtist) = Jf.artistItem(a).let { it.buildUpon().setMediaMetadata(it.mediaMetadata.buildUpon().setExtras(style(GRID)).build()).build() }

        // LISTAS: the server's lists, the same as the phone's and the PC's DM-90; in each, SEGUIR (from the song and
        // the second where it was left, here, on the phone or on the PC) and its songs
        private fun lists(id: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            background.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val out: List<MediaItem> = if (id == "jflists") {
                    val places = Jf.places()
                    Jf.lists().map { l ->
                        val at = places[l.id]?.optInt("i", -1) ?: -1
                        val sub = l.count.toString() + (if (l.count == 1) " tema" else " temas") + if (at >= 0 && at < l.count) " · quedó en el " + (at + 1) else ""
                        val extras = style().apply { if (at >= 0 && l.count > 0) {
                            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, ((at + 1.0) / l.count).coerceIn(0.0, 1.0)) } }
                        folder("jfplist:" + l.id, l.name, sub, CarArt.list(l.id, l.name, l.count), MediaMetadata.MEDIA_TYPE_PLAYLIST, extras)
                    }
                } else {
                    val list = id.removePrefix("jfplist:")
                    val songs = Jf.listSongs(list).take(500)
                    val left = listPlace(list, songs)
                    val go = if (songs.isEmpty()) null else if (left != null) playable("jflgo:$list", "SEGUIR", "Tema " + (left.first + 1) + " · " + songs[left.first].name + " · " + clock(left.second), CarArt.icon("resume"))
                        else playable("jflgo:$list", "DESDE EL PRINCIPIO", songs.size.toString() + if (songs.size == 1) " tema" else " temas", CarArt.icon("resume"))
                    listOfNotNull(go) + songs.mapIndexed { i, s -> Jf.item(s, "list:$list").let { it.buildUpon().setMediaId("jfl:$list:$i").setMediaMetadata(it.mediaMetadata.buildUpon()
                        .setSubtitle(if (left?.first == i) "Quedó acá · " + clock(left.second) else s.artist).setExtras(Bundle(it.mediaMetadata.extras ?: Bundle()).apply {
                            if (left?.first == i) putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) }).build()).build() } }
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(out), params)
            }
        private fun clock(ms: Long) = (ms / 1000).let { "%d:%02d".format(java.util.Locale.ROOT, it / 60, it % 60) }
        // where a list was left: which of its songs and the millisecond (only if that song is still there)
        private fun listPlace(list: String, songs: List<JfSong>): Pair<Int, Long>? = Jf.place(list)?.let { p ->
            val i = p.optInt("i", -1)
            if (i in songs.indices && songs[i].id == p.optString("song")) i to p.optLong("t") * 1000 else songs.indexOfFirst { it.id == p.optString("song") }.takeIf { it >= 0 }?.let { it to p.optLong("t") * 1000 }
        }

        // a list played from the car: all of it, from SEGUIR (where it was left) or from a song (from its start, or
        // from where it was left if it's that one)
        private fun listQueue(id: String): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = background.submit<MediaSession.MediaItemsWithStartPosition> {
            val go = id.startsWith("jflgo:")
            val list = if (go) id.removePrefix("jflgo:") else id.removePrefix("jfl:").substringBeforeLast(":")
            val songs = Jf.listSongs(list).take(500)
            val left = listPlace(list, songs)
            val at = (if (go) left?.first ?: 0 else id.substringAfterLast(":").toIntOrNull() ?: 0).coerceIn(0, maxOf(0, songs.size - 1))
            val pos = if (left != null && left.first == at) left.second else 0L
            store.jfQueue = Jf.queueJson(songs); store.jfIndex = at; store.jfPos = pos; store.mode = "server"; store.jfList = list; Now.error = ""
            Music.set("srv")
            MediaSession.MediaItemsWithStartPosition(songs.map { Jf.item(it, "list:$list") }, at, pos)
        }

        // a SERVER song asked for alone (by the car): its album from there; AL AZAR: 100 at random. Kept for resumption
        private fun serverQueue(id: String): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = background.submit<MediaSession.MediaItemsWithStartPosition> {
            val songs: List<JfSong>; var at = 0
            if (id == "jfshuffle") songs = Jf.shuffle()
            else if (id == "telshuffle") { songs = Tel.shuffle(); Music.set("tel") }
            else {
                val s = Jf.song(Jf.parse(id) ?: "")
                val album = s?.albumId.orEmpty()
                val all = if (album.isNotEmpty()) Music.songsOf(album) else emptyList()
                songs = all.ifEmpty { listOfNotNull(s) }
                at = songs.indexOfFirst { "jf:" + it.id == id }.coerceAtLeast(0)
            }
            store.jfQueue = Jf.queueJson(songs); store.jfIndex = at; store.jfPos = 0; store.mode = "server"; store.jfList = ""; Now.error = ""
            MediaSession.MediaItemsWithStartPosition(songs.map { Jf.item(it) }, at, C.TIME_UNSET)
        }

        // LOCALES in the car: found again where the phone is now, when it has moved or the list is old (a few seconds,
        // off the main thread); without the location, the last ones found
        private fun local(params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = { ImmutableList.copyOf((1..Bands.SLOTS).mapNotNull { store.item(Bands.LOCAL, it) }) }
            val where = Local.lastKnown(this@RadioService)
            if (where == null || !Local.stale(store, where.latitude, where.longitude))
                return Futures.immediateFuture(LibraryResult.ofItemList(items(), params))
            return background.submit<LibraryResult<ImmutableList<MediaItem>>> {
                runCatching { Local.refresh(this@RadioService, store, where.latitude, where.longitude) }
                LibraryResult.ofItemList(items(), params)
            }
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = store.itemFor(mediaId)
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        // a memory asked for (by the car or the phone, with only its id): its whole band, starting there
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val askedId = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)?.mediaId
            if (mediaItems.size <= 1 && (askedId?.startsWith("jflgo:") == true || askedId?.startsWith("jfl:") == true)) return listQueue(askedId)
            if (mediaItems.size <= 1 && (askedId == "jfshuffle" || askedId == "telshuffle" || Jf.parse(askedId) != null)) return serverQueue(askedId!!)
            Cd.parse(askedId)?.let { n -> if (mediaItems.size <= 1) { val (items, at) = Cd.playlist(n); Now.error = ""
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET)) } }
            val song = Usb.parse(askedId)
            if (mediaItems.size <= 1 && song != null) {                   // a song: its whole disc, from there
                val (items, at) = Usb.playlist(song.first, song.second)
                Now.error = ""
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET))
            }
            val asked = Store.parse(askedId)
            if (mediaItems.size <= 1 && asked != null) {
                val (items, at) = store.playlist(asked.first, asked.second)
                retried = ""; Now.error = ""
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET))
            }
            val full = mediaItems.mapNotNull { m -> store.itemFor(m.mediaId) }
            if (full.isNotEmpty()) { retried = ""; Now.error = "" }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(full, startIndex.coerceIn(0, maxOf(0, full.size - 1)), C.TIME_UNSET))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { m -> store.itemFor(m.mediaId) }.toMutableList())

        // the car starts (or "play" on the headphones): the last station heard
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (store.mode == "server" && (Jf.signedIn || Tel.ready)) {
                val q = Jf.queueFrom(store.jfQueue)
                if (q.isNotEmpty()) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(q.map { Jf.item(it) }, store.jfIndex.coerceIn(0, q.size - 1), store.jfPos))
            }
            if (store.mode == "cd" && Cd.item(store.cdTrack) != null) {
                val (items, at) = Cd.playlist(store.cdTrack)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET))
            }
            if (store.mode == "usb" && Usb.item(store.usbDisc, store.usbTrack) != null) {
                val (items, at) = Usb.playlist(store.usbDisc, store.usbTrack)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, store.usbPos))
            }
            if (store.onTuned) store.tunedItem()?.let { return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(it), 0, C.TIME_UNSET)) }
            val (items, at) = store.playlist(store.band, store.slot)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET))
        }
    }

    // the player's speakers, with the meters' tap in between (Levels)
    private class MeterRenderers(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(Levels))).build()

        override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>) {
            out.add(TimedAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink))
        }
    }

    // the platform's decoders, as the factory's own, telling Levels how far ahead of the ear the sound goes into the
    // sink: a buffer's time minus the time being heard (both on the renderer's clock, which follows the speakers)
    private class TimedAudioRenderer(context: Context, codecs: MediaCodecAdapter.Factory, selector: MediaCodecSelector, fallback: Boolean,
        handler: Handler, listener: AudioRendererEventListener, sink: AudioSink) :
        MediaCodecAudioRenderer(context, codecs, selector, fallback, handler, listener, sink) {
        override fun processOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long, codec: MediaCodecAdapter?, buffer: ByteBuffer?,
            bufferIndex: Int, bufferFlags: Int, sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean,
            isLastBuffer: Boolean, format: Format): Boolean {
            val done = super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount,
                bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format)
            if (done && !isDecodeOnlyBuffer && state == Renderer.STATE_STARTED) Levels.lead((bufferPresentationTimeUs - positionUs) / 1000.0)
            return done
        }
    }
}

// deck90://station/FM2/3 → the station's stream address (on the player's loading thread: it may ask Radio Browser)
@UnstableApi
class Resolver(private val store: Store) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val u = dataSpec.uri
        if (u.scheme != "deck90") return dataSpec
        if (u.host == "tuned") return dataSpec.withUri(Uri.parse(address(store.tuned ?: throw IOException("no tuned station"))))
        if (u.host == "jf") {                                            // SERVER, with its token (0.1.15: in the header)
            val id = u.lastPathSegment ?: throw IOException("bad song")
            return dataSpec.withUri(Uri.parse(Jf.stream(id))).withAdditionalHeaders(Jf.streamHeaders(id))
        }
        val parts = u.pathSegments
        val band = parts.getOrNull(0) ?: throw IOException("bad station")
        val slot = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad station")
        val st = store.station(band, slot) ?: throw IOException("empty memory")
        return dataSpec.withUri(Uri.parse(address(st)))
    }

    private fun address(st: Station): String {
        val key = st.q + "|" + st.cc
        val bad = failed.remove(key)                                 // what failed last time is tried last
        val url = (listOfNotNull(st.url.ifEmpty { null }, store.resolved(st)).filter { it != bad }.firstOrNull())
            ?: RadioBrowser.find(st.q, st.cc).filter { RadioBrowser.fits(st.q, it.name) }.map { it.url }.firstOrNull { it != bad }   // 0.1.26: that station only
            ?: bad ?: throw IOException("no station found for " + st.q)
        if (url != st.url) store.resolvedSet(st, url)
        last[key] = url
        return url
    }

    companion object {
        private val last = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
        // after an error: the next try skips the address that failed
        fun fail(st: Station) { last[st.q + "|" + st.cc]?.let { failed[st.q + "|" + st.cc] = it } }
    }
}

// What the phone's front shows besides the player's own state: the song (ICY), a problem, the stream's quality
object Now {
    @Volatile var song = ""
    @Volatile var error = ""
    @Volatile var quality = ""
    @Volatile var version = 0; private set
    fun changed() { version++ }

    // the stereo on or off (PWR, or SLEEP at its end), and the word the display says when it goes off or comes on:
    // GOOD BYE (PWR or SLEEP), HELLO, for a moment
    @Volatile var power = true; private set
    @Volatile var greet = ""; private set
    @Volatile var greetUntil = 0L; private set
    fun off(word: String) { power = false; say(word, 1800) }
    fun on() { if (!power) { power = true; say("HELLO", 1200) } }
    fun awake() { power = true }                                     // playing from elsewhere (the car, the notification): on, no word
    private fun say(word: String, ms: Long) { greet = word; greetUntil = System.currentTimeMillis() + ms; changed() }
    fun greeting(now: Long = System.currentTimeMillis()): String { if (greetUntil in 1 until now) { greet = ""; greetUntil = 0 }; return greet }
}
