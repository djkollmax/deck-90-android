package com.djkollmax.deck90

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

// ═══════════════════════════════════════
//  SERVER's sources (0.1.10): SRV, the person's Jellyfin (Jellyfin.kt), and TEL, the music on the phone or the tablet
//  and its memory card, from the library Android keeps of it (MediaStore: every song, with its artist, album, number,
//  year and length, kept up to date by Android itself). Both answer the same calls (MusicLib), so the face's lists,
//  the car's and the player are one for the two. A TEL song is "t" and its number in Android's library ("t1234"), an
//  album "t" and its album's number: they play and show through the same jf: ids as the server's, each going to its
//  own place by that "t" (Jf.stream, Jf.cover). Reading the music needs Android's permission, asked the first time.
// ═══════════════════════════════════════
interface MusicLib {
    val ready: Boolean
    val albums: List<JfAlbum>
    val artists: List<JfArtist>
    val reading: Boolean
    val error: String
    val readAt: Long
    fun read(force: Boolean = false): Boolean
    fun songsOf(albumId: String): List<JfSong>
    fun albumsOf(artistId: String): List<JfAlbum>
    fun recent(): List<JfAlbum>
    fun shuffle(count: Int = 100): List<JfSong>
    fun search(text: String): Triple<List<JfArtist>, List<JfAlbum>, List<JfSong>>
}

// which source SERVER shows: "srv" or "tel" (kept), and the calls that go to the one an id belongs to
object Music {
    private const val PREFS = "music"
    @Volatile var source = "srv"; private set
    private var app: Context? = null
    fun load(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        source = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("source", "srv").let { if (it == "tel") "tel" else "srv" }
        Tel.load(ctx)
    }
    fun set(s: String) {
        source = if (s == "tel") "tel" else "srv"
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("source", source)?.apply()
        Now.changed()
    }
    val tel get() = source == "tel"
    fun now(): MusicLib = if (tel) Tel else Jf
    fun of(id: String): MusicLib = if (Tel.mine(id)) Tel else Jf
    fun songsOf(albumId: String) = of(albumId).songsOf(albumId)
    fun albumsOf(artistId: String) = of(artistId).albumsOf(artistId)
    // what the display says under PLAY
    fun label(id: String?): String = if (id != null && Tel.mine(id)) "TEL" else if (id == null && tel) "TEL" else "SRV"
}

object Tel : MusicLib {
    @Volatile override var albums: List<JfAlbum> = emptyList(); private set
    @Volatile override var artists: List<JfArtist> = emptyList(); private set
    @Volatile override var reading = false; private set
    @Volatile override var error = ""; private set
    @Volatile override var readAt = 0L; private set
    @Volatile private var songs: List<JfSong> = emptyList()
    @Volatile private var byAlbum: Map<String, List<JfSong>> = emptyMap()
    @Volatile private var added: Map<String, Long> = emptyMap()               // an album's newest song, for RECIENTES
    private var app: Context? = null

    fun load(ctx: Context) { if (app == null) app = ctx.applicationContext }

    private val ID = Regex("^t\\d{1,19}$")
    fun mine(id: String) = ID.matches(id)

    // the permission Android asks for: its music (13 and later), or its storage (before)
    val permission: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    override val ready: Boolean get() = app?.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // apps' files, voice notes and recordings that Android may count as music
    private val NOT_MUSIC = Regex("(^|/)(android/|whatsapp|telegram|recordings/|call|voice recorder|notifications/|ringtones/|alarms/)", RegexOption.IGNORE_CASE)

    // Android's library read (fast: one query); blocking, a thread of its own
    override fun read(force: Boolean): Boolean {
        val ctx = app ?: return false
        if (!ready) { error = "permission"; return false }
        if (!force && songs.isNotEmpty() && System.currentTimeMillis() - readAt < 5 * 60_000L) return true
        if (reading) { val t0 = System.currentTimeMillis(); while (reading && songs.isEmpty() && System.currentTimeMillis() - t0 < 30_000) Thread.sleep(100); return songs.isNotEmpty() }   // 0.1.11: wait for it
        reading = true; Now.changed()
        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val cols = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) cols += MediaStore.Audio.Media.RELATIVE_PATH
            if (Build.VERSION.SDK_INT >= 30) { cols += MediaStore.Audio.Media.ALBUM_ARTIST; cols += MediaStore.Audio.Media.BITRATE }
            if (Build.VERSION.SDK_INT >= 34) { cols += "samplerate"; cols += "bits_per_sample" }
            val out = ArrayList<JfSong>(); val addedAt = HashMap<String, Long>(); val albumArtist = HashMap<String, String>()
            ctx.contentResolver.query(uri, cols.toTypedArray(), MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)?.use { c ->
                val ix = cols.associateWith { c.getColumnIndex(it) }
                fun str(k: String) = ix[k]?.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                fun num(k: String) = ix[k]?.takeIf { it >= 0 }?.let { if (c.isNull(it)) 0L else c.getLong(it) } ?: 0L
                while (c.moveToNext()) {
                    if (NOT_MUSIC.containsMatchIn(str(MediaStore.Audio.Media.RELATIVE_PATH))) continue
                    val len = (num(MediaStore.Audio.Media.DURATION) / 1000).toInt()
                    if (len in 1 until 20) continue                                   // a ringtone or a voice note
                    val id = "t" + num(MediaStore.Audio.Media._ID); val album = "t" + num(MediaStore.Audio.Media.ALBUM_ID)
                    val track = num(MediaStore.Audio.Media.TRACK).toInt()
                    val mime = str(MediaStore.Audio.Media.MIME_TYPE).lowercase()
                    val codec = codecOf(mime, str(MediaStore.Audio.Media.DISPLAY_NAME))
                    val size = num(MediaStore.Audio.Media.SIZE)
                    val kbps = (num(MediaStore.Audio.Media.BITRATE) / 1000).toInt().takeIf { it > 0 } ?: if (len > 0) (size * 8 / len / 1000).toInt() else 0
                    val artist = str(MediaStore.Audio.Media.ARTIST).let { if (it == "<unknown>") "" else it }
                    val aa = str(MediaStore.Audio.Media.ALBUM_ARTIST).let { if (it == "<unknown>") "" else it }
                    if (aa.isNotEmpty()) albumArtist[album] = aa
                    out += JfSong(id, str(MediaStore.Audio.Media.TITLE).ifEmpty { str(MediaStore.Audio.Media.DISPLAY_NAME).substringBeforeLast('.') }.ifEmpty { "UNTITLED" },
                        artist, str(MediaStore.Audio.Media.ALBUM).let { if (it == "<unknown>") "" else it }, album, track % 1000, track / 1000, len,
                        codec, kbps, num("samplerate").toInt(), num("bits_per_sample").toInt())
                    val at = num(MediaStore.Audio.Media.DATE_ADDED); if (at > (addedAt[album] ?: 0L)) addedAt[album] = at
                }
            }
            val grouped = out.groupBy { it.albumId }.mapValues { (_, l) -> l.sortedWith(compareBy({ it.disc }, { it.n }, { Jf.sortKey(it.name) })) }
            val al = grouped.map { (aid, l) ->
                val who = albumArtist[aid] ?: l.map { it.artist }.distinct().let { if (it.size == 1) it[0] else if (it.isEmpty()) "" else "Various Artists" }
                JfAlbum(aid, l.first().album.ifEmpty { "UNTITLED" }, who.ifEmpty { "Unknown Artist" }, 0, artistId(who.ifEmpty { "Unknown Artist" }))
            }
            songs = out; byAlbum = grouped; added = addedAt
            albums = al.sortedBy { Jf.sortKey(it.name) }
            artists = al.map { it.artist }.distinct().map { JfArtist(artistId(it), it) }.sortedBy { Jf.sortKey(it.name) }
            Jf.know(out); readAt = System.currentTimeMillis(); error = ""
            return true
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; return false }
        finally { reading = false; Now.changed() }
    }
    private fun artistId(name: String) = "t" + (Jf.sortKey(name).hashCode().toLong() and 0xffffffffL)

    // "audio/flac", "audio/mpeg", "audio/mp4"…: the codec the display names
    fun codecOf(mime: String, name: String): String = when {
        "flac" in mime || name.endsWith(".flac", true) -> "flac"
        "mpeg" in mime || name.endsWith(".mp3", true) -> "mp3"
        "opus" in mime || name.endsWith(".opus", true) -> "opus"
        "ogg" in mime || "vorbis" in mime -> "vorbis"
        "wav" in mime -> "pcm_s16le"
        "alac" in mime -> "alac"
        "mp4" in mime || "aac" in mime || "m4a" in mime -> "aac"
        else -> ""
    }

    override fun songsOf(albumId: String): List<JfSong> { if (songs.isEmpty()) read(); return byAlbum[albumId].orEmpty() }
    override fun albumsOf(artistId: String): List<JfAlbum> { if (songs.isEmpty()) read(); return albums.filter { it.artistId == artistId } }
    override fun recent(): List<JfAlbum> { if (songs.isEmpty()) read(); return albums.sortedByDescending { added[it.id] ?: 0L }.take(60) }
    override fun shuffle(count: Int): List<JfSong> { if (songs.isEmpty()) read(); return songs.shuffled().take(count) }
    override fun search(text: String): Triple<List<JfArtist>, List<JfAlbum>, List<JfSong>> {
        if (songs.isEmpty()) read()
        val words = Jf.sortKey(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val hit = { s: String -> val k = Jf.sortKey(s); words.all { k.contains(it) } }
        return Triple(artists.filter { hit(it.name) }.take(30), albums.filter { hit(it.name + " " + it.artist) }.take(60),
            songs.filter { hit(it.name + " " + it.artist + " " + it.album) }.take(60))
    }

    // where a song's sound is, and an album's picture (Android's own)
    fun stream(id: String): String = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.removePrefix("t").toLong()).toString()
    fun cover(albumId: String): Uri? = albumId.removePrefix("t").toLongOrNull()?.let { ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it) }
}
