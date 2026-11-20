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
    private val background = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())   // LOCALES' lookups

    private lateinit var store: Store
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var jfSkips = 0
    private var retried = ""                                         // the station already retried after an error

    override fun onCreate() {
        super.onCreate()
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
                val usb = Usb.parse(item?.mediaId)
                val cd = Cd.parse(item?.mediaId)
                val jf = Jf.parse(item?.mediaId)
                if (jf != null) { store.mode = "server"; store.jfIndex = player.currentMediaItemIndex; store.jfPos = 0 }
                else if (cd != null) { store.mode = "cd"; store.cdTrack = cd }
                else if (usb != null) { store.mode = "usb"; store.usbDisc = usb.first; store.usbTrack = usb.second; store.usbPos = 0 }
                else if (item?.mediaId == Store.TUNED) { store.mode = "radio"; store.onTuned = true }
                else Store.parse(item?.mediaId)?.let { (b, s) -> store.mode = "radio"; store.band = b; store.slot = s; store.onTuned = false }
                Now.song = ""; Now.error = ""; Now.changed()
            }
            override fun onMediaMetadataChanged(m: MediaMetadata) { Now.changed() }
            // a song from USB: its own tags once the file is read (the item keeps them: asked once)
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val item = player.currentMediaItem ?: return
                if (Usb.parse(item.mediaId) == null || item.mediaMetadata.extras?.getBoolean("tags") == true) return
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) jfSkips = 0
                if (!isPlaying && Usb.parse(player.currentMediaItem?.mediaId) != null) store.usbPos = player.currentPosition   // the car comes back here
                if (!isPlaying && Jf.parse(player.currentMediaItem?.mediaId) != null) { store.jfIndex = player.currentMediaItemIndex; store.jfPos = player.currentPosition }
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
                        http?.responseCode == 401 -> { Jf.expired(); Now.error = "SIGN IN" }
                        http != null && jfSkips < 3 && player.hasNextMediaItem() -> { jfSkips++; Now.error = ""; player.seekToNextMediaItem(); player.prepare(); player.play() }
                        else -> Now.error = if (Jf.signedIn) "NO SERVER" else "SIGN IN"
                    }
                    Now.changed(); return
                }
                val st = (if (item.mediaId == Store.TUNED) store.tuned else Store.parse(item.mediaId)?.let { (b, s) -> store.station(b, s) }) ?: return
                store.resolvedSet(st, null)
                if (retried != item.mediaId) { retried = item.mediaId; Resolver.fail(st); player.prepare(); player.play() }
                else { Now.error = "Sin señal"; Now.changed() }
            }
        })
        session = MediaLibrarySession.Builder(this, player, Library()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()   // swiped away while silent: gone
    }

    override fun onDestroy() {
        Sleep.attach(null)
        background.shutdownNow()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    // the browse tree: the bands, each with its memories
    private inner class Library : MediaLibrarySession.Callback {
        private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()).build()

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Deck 90"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = when {
                parentId == "root" -> Bands.order.map { folder("band:$it", "RADIO " + Bands.label(it)) } + folder("band:${Bands.LOCAL}", "RADIOS LOCALES") +
                    (if (Usb.discs.isNotEmpty()) listOf(folder("usb", "USB · " + Usb.rootName.ifEmpty { "MÚSICA" })) else emptyList()) +
                    (if (Cd.count() > 0) listOf(folder("cd", "CD · " + (Cd.names?.album?.ifEmpty { null } ?: if (Cd.status == "files") "MP3" else "AUDIO"))) else emptyList()) +
                    (if (Jf.signedIn) listOf(folder("jf", "SERVER · MÚSICA")) else emptyList()) +
                    (if (Tel.ready) listOf(folder("tel", "TEL · MÚSICA DEL TELÉFONO")) else emptyList())        // 0.1.10
                parentId == "jf" || parentId.startsWith("jf") || parentId.startsWith("tel") -> return server(parentId, params)
                parentId == "cd" -> Cd.playlist(1).first
                parentId == "usb" -> (1..Usb.discs.size).mapNotNull { Usb.discItem(it) }
                parentId.startsWith("usbdisc:") -> parentId.removePrefix("usbdisc:").toIntOrNull()?.let { d -> Usb.playlist(d, 1).first } ?: emptyList()
                parentId == "band:${Bands.LOCAL}" -> return local(params)
                parentId.startsWith("band:") -> parentId.removePrefix("band:").let { b -> (1..Bands.SLOTS).mapNotNull { store.item(b, it) } }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
        }

        // SERVER in the car (Jellyfin.kt), and TEL (Tel.kt) the same from "tel": AL AZAR, RECIENTES, DISCOS and ARTISTAS
        // by letter, an artist's albums, an album's songs (those two by their own ids). What needs the server is asked
        // off the main thread
        private fun server(id: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            background.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val playable = { mid: String, title: String -> MediaItem.Builder().setMediaId(mid).setMediaMetadata(MediaMetadata.Builder().setTitle(title)
                    .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()).build() }
                val music = { mid: String, title: String -> MediaItem.Builder().setMediaId(mid).setMediaMetadata(MediaMetadata.Builder().setTitle(title)
                    .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build() }
                val tel = id.startsWith("tel"); val p = if (tel) "tel" else "jf"
                val lib: MusicLib = when { tel -> Tel; id.startsWith("jfartist:") -> Music.of(id.removePrefix("jfartist:")); id.startsWith("jfalbum:") -> Music.of(id.removePrefix("jfalbum:")); else -> Jf }
                lib.read()
                val list: List<MediaItem> = when {
                    id == p -> listOf(playable("${p}shuffle", "AL AZAR"), music("${p}recent", "RECIENTES"), music("${p}albums", "DISCOS (${lib.albums.size})"),
                        music("${p}artists", "ARTISTAS (${lib.artists.size})"))
                    id == "${p}recent" -> lib.recent().map { Jf.albumItem(it) }
                    id == "${p}albums" -> lib.albums.groupBy { Jf.letter(it.name) }.toSortedMap(compareBy { if (it == "#") "~" else it }).map { (l, a) -> music("${p}albums:$l", "$l · ${a.size}") }
                    id.startsWith("${p}albums:") -> id.removePrefix("${p}albums:").let { l -> lib.albums.filter { Jf.letter(it.name) == l }.map { Jf.albumItem(it) } }
                    id == "${p}artists" -> lib.artists.groupBy { Jf.letter(it.name) }.toSortedMap(compareBy { if (it == "#") "~" else it }).map { (l, a) -> music("${p}artists:$l", "$l · ${a.size}") }
                    id.startsWith("${p}artists:") -> id.removePrefix("${p}artists:").let { l -> lib.artists.filter { Jf.letter(it.name) == l }.map { Jf.artistItem(it) } }
                    id.startsWith("jfartist:") -> lib.albumsOf(id.removePrefix("jfartist:")).map { Jf.albumItem(it) }
                    id.startsWith("jfalbum:") -> id.removePrefix("jfalbum:").let { a -> lib.songsOf(a).map { Jf.item(it, "album:$a") } }
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
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
            store.jfQueue = Jf.queueJson(songs); store.jfIndex = at; store.jfPos = 0; store.mode = "server"; Now.error = ""
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
        if (u.host == "jf") return dataSpec.withUri(Uri.parse(Jf.stream(u.lastPathSegment ?: throw IOException("bad song"))))   // SERVER, with its token
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
            ?: RadioBrowser.find(st.q, st.cc).map { it.url }.firstOrNull { it != bad }
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
