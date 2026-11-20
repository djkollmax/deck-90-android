package com.djkollmax.deck90

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ═══════════════════════════════════════
//  USB: music from a pendrive (or any folder: the phone's, a memory card) as the stereo's discs. The person picks it
//  once (Android's own folder picker, which also shows a pendrive plugged in by OTG); every folder with music in it is
//  a disc, its songs in the order of their names (01, 02…). What's found is kept in a file, so the car and the face
//  have it at once; MODE (or EJECT) reads the folder again. The same pendrive plugged in again is the same folder.
// ═══════════════════════════════════════
data class Song(val name: String, val uri: String)
data class Disc(val name: String, val songs: List<Song>)

object Usb {
    // 0.1.10: what the phone plays (no WMA, no playlists, no MIDI: one of them stopped a disc with NO USB)
    private val AUDIO = Regex("\\.(mp3|flac|m4a|aac|ogg|oga|opus|wav)$", RegexOption.IGNORE_CASE)
    private val NOT_SONGS = Regex("\\.(m3u8?|pls|wpl|xspf|cue|mid|midi|amr|3gp|wma|ape|aiff?|dsf)$", RegexOption.IGNORE_CASE)
    // 0.1.10: the phone's own folders that aren't music (a memory card or the phone chosen whole): apps' files and
    // WhatsApp's voice notes, the ringtones and alarms, recordings
    private val NOT_MUSIC = Regex("^(android|notifications|ringtones|alarms|recordings|call recordings|voice recorder|sounds|whatsapp|telegram|dcim|lost\\.dir|\\\$recycle\\.bin|system volume information)$", RegexOption.IGNORE_CASE)
    fun isSong(name: String, mime: String): Boolean = !name.startsWith(".") && !NOT_SONGS.containsMatchIn(name) &&
        (AUDIO.containsMatchIn(name) || (mime.startsWith("audio/") && !mime.contains("mpegurl") && !mime.contains("scpls") && !mime.contains("midi")))
    fun isMusicFolder(name: String): Boolean = !name.startsWith(".") && !NOT_MUSIC.matches(name.trim())
    private const val MAX_DISCS = 2000
    private const val MAX_FOLDERS = 6000
    private const val MAX_DEPTH = 8

    @Volatile var discs: List<Disc> = emptyList(); private set
    @Volatile var root: String = ""; private set                     // the tree's uri ("" until one is picked)
    @Volatile var rootName: String = ""; private set
    @Volatile var scanning = false; private set
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "usb.json")

    // what was found last time (once per process)
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            root = o.optString("root"); rootName = o.optString("name")
            discs = fromJson(o.optJSONArray("discs") ?: JSONArray())
        }
    }

    // a folder picked (its access kept for good), then read
    fun pick(ctx: Context, tree: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        root = tree.toString(); rootName = ""; discs = emptyList(); save(ctx)
    }

    // the folder read again (blocking: a thread of its own). False when it can't be read (the pendrive is out)
    fun scan(ctx: Context): Boolean {
        load(ctx)
        val tree = root.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return false
        scanning = true; Now.changed()
        try {
            val top = DocumentsContract.getTreeDocumentId(tree)
            val name = runCatching { nameOf(ctx, tree, top) }.getOrNull() ?: return false
            val found = mutableListOf<Disc>()
            var folders = 0
            fun walk(id: String, title: String, parent: String, depth: Int) {
                if (found.size >= MAX_DISCS || folders++ >= MAX_FOLDERS) return
                val subs = mutableListOf<Pair<String, String>>(); val songs = mutableListOf<Song>()
                val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val kid = c.getString(0) ?: continue; val n = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                        if (n.startsWith(".")) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (isMusicFolder(n)) subs += kid to n }
                        else if (isSong(n, mime)) songs += Song(n, DocumentsContract.buildDocumentUriUsingTree(tree, kid).toString())
                    }
                }
                if (songs.isNotEmpty()) found += Disc(discName(title, parent), songs.sortedWith(compareBy(natural) { it.name }).take(999))
                if (depth < MAX_DEPTH) for ((kid, n) in subs.sortedWith(compareBy(natural) { it.second })) walk(kid, n, title, depth + 1)
            }
            walk(top, name, "", 0)
            rootName = name; discs = found; save(ctx)
            return true
        } catch (e: Exception) {
            return false
        } finally { scanning = false; Now.changed() }
    }

    private fun nameOf(ctx: Context, tree: Uri, id: String): String? =
        ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    // "CD1" or "Disc 2" says little alone: with the folder above it
    fun discName(title: String, parent: String): String =
        if (parent.isNotEmpty() && Regex("^(cd|disc|disco|disk)\\s*\\d+$", RegexOption.IGNORE_CASE).matches(title.trim())) "$parent · $title" else title

    // a song's name from its file: no extension, no track number in front ("01 - Tema.flac", "01. Tema", "1 Tema")
    fun songName(file: String): String = file.replace(Regex("\\.[A-Za-z0-9]{2,5}$"), "")
        .replace(Regex("^\\s*(\\d{1,3}|[A-Da-d]\\d{1,2})\\s*([-._)]\\s*|\\s+)"), "").trim().ifEmpty { file }

    // names compared as people read them: "2" before "10"
    val natural = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0')) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size.compareTo(rb.size)
    }

    private fun save(ctx: Context) = runCatching {
        val a = JSONArray(discs.map { d -> JSONObject().put("n", d.name).put("s", JSONArray(d.songs.map { JSONArray().put(it.name).put(it.uri) })) })
        file(ctx).writeText(JSONObject().put("root", root).put("name", rootName).put("discs", a).toString())
    }
    fun fromJson(a: JSONArray): List<Disc> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val s = o.optJSONArray("s") ?: return@mapNotNull null
        Disc(o.optString("n"), (0 until s.length()).mapNotNull { j -> s.optJSONArray(j)?.let { Song(it.optString(0), it.optString(1)) } })
    }

    // the player's and the car's items: "usb:3:7" a song (disc 3, its 7th: both from 1), "usbdisc:3" a disc
    fun mediaId(d: Int, t: Int) = "usb:$d:$t"
    fun parse(id: String?): Pair<Int, Int>? {
        val m = Regex("^usb:(\\d+):(\\d+)$").matchEntire(id ?: return null) ?: return null
        val d = m.groupValues[1].toInt(); val t = m.groupValues[2].toInt()
        return if (d in 1..discs.size && t in 1..discs[d - 1].songs.size) d to t else null
    }

    fun item(d: Int, t: Int): MediaItem? {
        val disc = discs.getOrNull(d - 1) ?: return null; val song = disc.songs.getOrNull(t - 1) ?: return null
        return MediaItem.Builder().setMediaId(mediaId(d, t)).setUri(song.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(songName(song.name)).setAlbumTitle(disc.name).setArtist(disc.name)
                .setTrackNumber(t).setTotalTrackCount(disc.songs.size)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
            .build()
    }
    fun discItem(d: Int): MediaItem? {
        val disc = discs.getOrNull(d - 1) ?: return null
        return MediaItem.Builder().setMediaId("usbdisc:$d").setMediaMetadata(MediaMetadata.Builder().setTitle(disc.name)
            .setSubtitle("${disc.songs.size} temas").setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM).build()).build()
    }
    // a disc's songs, and where the one asked for is among them
    fun playlist(d: Int, t: Int): Pair<List<MediaItem>, Int> =
        (1..(discs.getOrNull(d - 1)?.songs?.size ?: 0)).mapNotNull { item(d, it) } to (t - 1).coerceAtLeast(0)
}

// a song's own tags (ID3, Vorbis comments, MP4), as the player reads them from the file: they replace the names taken
// from the file and its folder, for the car and the face
object Tags {
    data class Found(val title: String?, val artist: String?, val album: String?, val art: ByteArray?)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun read(tracks: androidx.media3.common.Tracks): Found? {
        var title: String? = null; var artist: String? = null; var album: String? = null; var albumArtist: String? = null; var art: ByteArray? = null
        for (g in tracks.groups) for (i in 0 until g.length) {
            val md = g.getTrackFormat(i).metadata ?: continue
            for (k in 0 until md.length()) when (val e = md.get(k)) {
                is androidx.media3.extractor.metadata.id3.TextInformationFrame -> {
                    val v = e.values.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    when (e.id) { "TIT2", "TT2" -> title = title ?: v; "TPE1", "TP1" -> artist = artist ?: v; "TALB", "TAL" -> album = album ?: v; "TPE2", "TP2" -> albumArtist = albumArtist ?: v }
                }
                is androidx.media3.extractor.metadata.vorbis.VorbisComment -> {
                    val v = e.value.trim().takeIf { it.isNotEmpty() } ?: continue
                    when (e.key.uppercase()) { "TITLE" -> title = title ?: v; "ARTIST" -> artist = artist ?: v; "ALBUM" -> album = album ?: v; "ALBUMARTIST" -> albumArtist = albumArtist ?: v }
                }
                is androidx.media3.extractor.metadata.id3.ApicFrame -> art = art ?: e.pictureData
                is androidx.media3.extractor.metadata.flac.PictureFrame -> art = art ?: e.pictureData
                else -> {}
            }
        }
        if (title == null && artist == null && album == null && art == null) return null
        return Found(title, artist ?: albumArtist, album, art)
    }
}
