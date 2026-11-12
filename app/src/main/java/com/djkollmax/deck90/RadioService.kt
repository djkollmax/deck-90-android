package com.djkollmax.deck90

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
import java.io.IOException

// ═══════════════════════════════════════
//  THE RADIO: one player for the phone and for Android Auto. Its browse tree is the bands and their memories; a
//  memory plays with the rest of its band as the list (so ◀◀ ▶▶ in the car change stations). A memory's address is
//  found when it's played (Resolver): the one it came with, the one that played last, or Radio Browser's best.
//  The song a station sends (ICY) goes to the session as the title, the station's name as the artist.
// ═══════════════════════════════════════
@UnstableApi
class RadioService : MediaLibraryService() {
    private lateinit var store: Store
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var retried = ""                                         // the station already retried after an error

    override fun onCreate() {
        super.onCreate()
        BuildConfigInfo.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" }.getOrDefault("0")
        store = Store(this)
        val http = DefaultHttpDataSource.Factory().setUserAgent("Deck90-Android/" + BuildConfigInfo.version)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val data = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), Resolver(store))
        player = ExoPlayer.Builder(this, MeterRenderers(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                Store.parse(item?.mediaId)?.let { (b, s) -> store.band = b; store.slot = s }
                Now.song = ""; Now.changed()
            }
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is IcyInfo) { Now.song = (e.title ?: "").trim(); Now.changed() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { Now.changed() }
            override fun onPlayerError(error: PlaybackException) {
                // the address stopped answering: forgotten, and the station asked of Radio Browser once more
                val item = player.currentMediaItem ?: return
                val (b, s) = Store.parse(item.mediaId) ?: return
                val st = store.station(b, s) ?: return
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
                parentId == "root" -> Bands.order.map { folder("band:$it", "RADIO " + Bands.label(it)) }
                parentId.startsWith("band:") -> parentId.removePrefix("band:").let { b -> (1..Bands.SLOTS).mapNotNull { store.item(b, it) } }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = Store.parse(mediaId)?.let { (b, s) -> store.item(b, s) }
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        // a memory asked for (by the car or the phone, with only its id): its whole band, starting there
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val asked = Store.parse(mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)?.mediaId)
            if (mediaItems.size <= 1 && asked != null) {
                val (items, at) = store.playlist(asked.first, asked.second)
                retried = ""; Now.error = ""
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET))
            }
            val full = mediaItems.mapNotNull { m -> Store.parse(m.mediaId)?.let { (b, s) -> store.item(b, s) } }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(full, startIndex.coerceIn(0, maxOf(0, full.size - 1)), C.TIME_UNSET))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { m -> Store.parse(m.mediaId)?.let { (b, s) -> store.item(b, s) } }.toMutableList())

        // the car starts (or "play" on the headphones): the last station heard
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val (items, at) = store.playlist(store.band, store.slot)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, at, C.TIME_UNSET))
        }
    }

    // the player's speakers, with the meters' tap in between (Levels)
    private class MeterRenderers(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(Levels))).build()
    }
}

// deck90://station/FM2/3 → the station's stream address (on the player's loading thread: it may ask Radio Browser)
@UnstableApi
class Resolver(private val store: Store) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val u = dataSpec.uri
        if (u.scheme != "deck90") return dataSpec
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
}
