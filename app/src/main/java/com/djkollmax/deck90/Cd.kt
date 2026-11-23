package com.djkollmax.deck90

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// ═══════════════════════════════════════
//  THE CD (MODE, or the slot on the face): a disc in a USB drive (CdDrive). An audio CD's tracks play as the disc
//  holds them (a WAV stream made from READ CD: the player seeks in it as in a file), named by MusicBrainz from the
//  disc's table of contents, as the DC-90 on the PC (electron/cdnames.js), with the cover from the Cover Art Archive.
//  A data disc (MP3, FLAC… burned on a CD or DVD) is read as ISO 9660 (Joliet's long names when it has them): its
//  music files, in order. Media ids "cd:3" (a track) and "cdf:12" (a file); addresses cdda://track/3, cdda://file/12.
// ═══════════════════════════════════════
data class CdTrack(val n: Int, val lba: Int, val sectors: Int)
data class CdFile(val path: String, val lba: Int, val size: Long)
data class CdNames(val album: String, val artist: String, val year: String, val titles: Map<Int, Pair<String, String>>, val release: String)

object Cd {
    // none (no drive), perm (waiting for the person's yes), nodisc, reading, audio, files, empty (no music), error
    @Volatile var status = "none"; private set
    @Volatile var tracks: List<CdTrack> = emptyList(); private set
    @Volatile var files: List<CdFile> = emptyList(); private set
    @Volatile var names: CdNames? = null; private set
    @Volatile var art: ByteArray? = null; private set
    @Volatile var id = ""; private set                               // MusicBrainz's disc id
    @Volatile var drive: CdDrive? = null; private set
    private val AUDIO = Regex("\\.(mp3|flac|m4a|aac|ogg|oga|opus|wav)$", RegexOption.IGNORE_CASE)

    fun count() = if (status == "files") files.size else tracks.size

    // the drive and the disc looked at again (blocking: off the main thread). The person's yes to the drive is the
    // activity's (UsbManager's own dialog); without it status is "perm"
    @Volatile private var failedAt = 0L
    // after a failure the drive is left alone a while: opening and resetting it over and over upsets it
    fun resting(now: Long = System.currentTimeMillis()) = status == "error" && now - failedAt < 15_000
    @Synchronized fun refresh(ctx: Context): String {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = CdDrive.find(usb)
        if (dev == null) { forget(); return set("none") }
        if (!usb.hasPermission(dev)) { forget(); return set("perm") }
        try {
            val d = drive ?: CdDrive.open(usb, dev).also { drive = it }
            if (!d.ready()) { tracks = emptyList(); files = emptyList(); names = null; art = null; id = ""; return set("nodisc") }
            if ((status == "audio" || status == "files") && !d.changed) return status   // the same disc still in
            d.changed = false
            set("reading")
            val toc = d.toc()
            val lead = toc.firstOrNull { it.n == 0xAA }?.lba ?: throw IOException("no lead-out")
            val list = toc.filter { it.n in 1..99 }
            val audio = list.filter { it.audio }.mapNotNull { t ->
                val end = list.firstOrNull { it.n == t.n + 1 }?.let { if (it.audio) it.lba else it.lba - 11400 } ?: lead
                (end - t.lba).takeIf { it > 0 }?.let { CdTrack(t.n, t.lba, it) }
            }
            if (audio.isNotEmpty()) {
                tracks = audio; files = emptyList()
                id = discId(audio, list, lead)
                set("audio")
                Thread { lookup(ctx) }.start()
                return status
            }
            // 0.1.15: a disc burned in several sessions has its whole file system in the last one (the first held
            // only what was burned first)
            val data = list.lastOrNull { !it.audio } ?: return set("empty")
            files = (runCatching { Iso(d, data.lba).music(AUDIO) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: list.firstOrNull { !it.audio }?.takeIf { it !== data }?.let { Iso(d, it.lba).music(AUDIO) }.orEmpty()).take(999)
            tracks = emptyList()
            return set(if (files.isEmpty()) "empty" else "files")
        } catch (e: Exception) {
            android.util.Log.w("Deck90", "CD: " + e)
            forget(); failedAt = System.currentTimeMillis(); return set("error")
        }
    }

    private fun set(s: String): String { status = s; Now.changed(); return s }
    private fun forget() { runCatching { drive?.close() }; drive = null; tracks = emptyList(); files = emptyList(); names = null; art = null; id = "" }

    fun eject(): Boolean {
        val d = drive ?: return false
        return runCatching { d.eject() }.isSuccess.also { if (it) { tracks = emptyList(); files = emptyList(); names = null; art = null; id = ""; set("nodisc") } }
    }

    // MusicBrainz disc id: the first and last audio track, the lead-out and the 99 offsets (+150), hex, SHA-1, base64
    // with . _ - (an enhanced CD's data track after the audio moves the lead-out 11400 sectors before it; 0.1.15: a
    // mixed-mode CD's audio starts at track 2)
    fun discId(audio: List<CdTrack>, all: List<CdDrive.TocEntry>, lead: Int): String {
        val last = audio.last().n
        val next = all.firstOrNull { it.n == last + 1 && !it.audio }
        val leadout = (if (next != null) next.lba - 11400 else lead) + 150
        val sb = StringBuilder().append("%02X".format(audio.first().n)).append("%02X".format(last)).append("%08X".format(leadout))
        for (i in 1..99) sb.append("%08X".format(audio.firstOrNull { it.n == i }?.let { it.lba + 150 } ?: 0))
        val sha = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getEncoder().encodeToString(sha).replace('+', '.').replace('/', '_').replace('=', '-')
    }
    fun tocParam(audio: List<CdTrack>, leadout: Int): String = (listOf(audio.first().n, audio.last().n, leadout + 150) + audio.map { it.lba + 150 }).joinToString("+")

    // the names, asked once per disc and kept (cd-names.json); the cover with them
    private fun lookup(ctx: Context) {
        val disc = id; if (disc.isEmpty()) return
        val cache = File(ctx.filesDir, "cd-names.json")
        val all = runCatching { JSONObject(cache.readText()) }.getOrDefault(JSONObject())
        var o = all.optJSONObject(disc)
        if (o == null) {
            val t = tracks; val lead = t.last().lba + t.last().sectors
            val j = runCatching { get("https://musicbrainz.org/ws/2/discid/" + Uri.encode(disc) + "?toc=" + tocParam(t, lead) + "&inc=recordings+artist-credits&fmt=json") }.getOrNull()
                ?.let { JSONObject(it) } ?: return
            o = parseNames(j, disc, t.size) ?: JSONObject().put("album", "")
            all.put(disc, o); runCatching { cache.writeText(all.toString()) }
        }
        if (id != disc || o.optString("album").isEmpty()) return
        names = namesFrom(o); Now.changed()
        o.optString("release").takeIf { Regex("^[0-9a-f-]{36}$").matches(it) }?.let { rel ->
            runCatching { bytes("https://coverartarchive.org/release/$rel/front-250") }.getOrNull()?.takeIf { it.size in 1..600_000 }?.let { if (id == disc) { art = it; Now.changed() } }
        }
    }
    fun parseNames(j: JSONObject, disc: String, count: Int): JSONObject? {
        val rels = j.optJSONArray("releases") ?: return null
        for (r in 0 until rels.length()) {
            val rel = rels.getJSONObject(r)
            val media = rel.optJSONArray("media") ?: continue
            var m: JSONObject? = null
            for (i in 0 until media.length()) { val x = media.getJSONObject(i); val ds = x.optJSONArray("discs"); if (ds != null && (0 until ds.length()).any { ds.getJSONObject(it).optString("id") == disc }) { m = x; break } }
            if (m == null) for (i in 0 until media.length()) { val x = media.getJSONObject(i); if (x.optInt("track-count", x.optJSONArray("tracks")?.length() ?: 0) == count) { m = x; break } }
            val tr = m?.optJSONArray("tracks") ?: continue
            val albumArtist = credit(rel.optJSONArray("artist-credit"))
            val titles = JSONObject()
            for (i in 0 until tr.length()) {
                val t = tr.getJSONObject(i)
                val n = t.optInt("position", t.optString("number").toIntOrNull() ?: (i + 1))
                titles.put(n.toString(), JSONObject().put("t", t.optString("title").ifEmpty { t.optJSONObject("recording")?.optString("title").orEmpty() }.take(200))
                    .put("a", credit(t.optJSONArray("artist-credit")).ifEmpty { albumArtist }.take(200)))
            }
            return JSONObject().put("album", rel.optString("title").take(200)).put("artist", albumArtist.take(200))
                .put("year", rel.optString("date").take(4)).put("titles", titles).put("release", rel.optString("id"))
        }
        return null
    }
    private fun credit(a: org.json.JSONArray?): String = if (a == null) "" else (0 until a.length()).joinToString("") { i ->
        val c = a.getJSONObject(i); (c.optString("name").ifEmpty { c.optJSONObject("artist")?.optString("name").orEmpty() }) + c.optString("joinphrase") }.trim()
    fun namesFrom(o: JSONObject): CdNames {
        val t = o.optJSONObject("titles") ?: JSONObject()
        val map = t.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { n -> t.optJSONObject(k)?.let { n to (it.optString("t") to it.optString("a")) } } }.toMap()
        return CdNames(o.optString("album"), o.optString("artist"), o.optString("year"), map, o.optString("release"))
    }
    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000; readTimeout = 12_000; setRequestProperty("User-Agent", "Deck90-Android/" + BuildConfigInfo.version + " ( github.com/djkollmax/deck-90-android )")
        setRequestProperty("Accept", "application/json")
    }
    private fun get(url: String): String? { val c = open(url); return if (c.responseCode == 404) null else c.inputStream.bufferedReader().use { it.readText() } }
    private fun bytes(url: String): ByteArray? { val c = open(url); return if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() } }

    // the player's items and the car's
    fun mediaId(i: Int) = if (status == "files") "cdf:$i" else "cd:$i"
    fun parse(id: String?): Int? {
        val m = Regex("^cd(f?):(\\d+)$").matchEntire(id ?: return null) ?: return null
        val i = m.groupValues[2].toInt()
        return if (m.groupValues[1] == "f") i.takeIf { status == "files" && it in 1..files.size } else i.takeIf { status == "audio" && tracks.any { t -> t.n == it } }
    }
    fun title(i: Int): String = if (status == "files") files.getOrNull(i - 1)?.path?.substringAfterLast('/')?.let(Usb::songName).orEmpty()
        else names?.titles?.get(i)?.first?.ifEmpty { null } ?: "TRACK " + i.toString().padStart(2, '0')
    fun artist(i: Int): String = if (status == "files") files.getOrNull(i - 1)?.path?.substringBeforeLast('/', "")?.substringAfterLast('/').orEmpty()
        else names?.titles?.get(i)?.second?.ifEmpty { null } ?: names?.artist.orEmpty()
    fun item(i: Int): MediaItem? {
        if (parse(mediaId(i)) == null) return null
        val files = status == "files"
        val m = MediaMetadata.Builder().setTitle(title(i)).setArtist(artist(i)).setAlbumTitle(if (files) "CD" else names?.album ?: "CD")
            .setTrackNumber(i).setTotalTrackCount(count()).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (!files) art?.let { m.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(mediaId(i)).setUri(if (files) "cdda://file/$i" else "cdda://track/$i").setMediaMetadata(m.build()).build()
    }
    fun numbers(): List<Int> = if (status == "files") (1..files.size).toList() else tracks.map { it.n }
    fun playlist(i: Int): Pair<List<MediaItem>, Int> { val all = numbers(); return all.mapNotNull { item(it) } to all.indexOf(i).coerceAtLeast(0) }
}

// a data disc's file system (ISO 9660; Joliet's long names when the disc has them): its music files, by path
class Iso(private val sector: (Int) -> ByteArray, private val start: Int) {
    constructor(d: CdDrive, start: Int) : this({ lba -> ByteArray(CdDrive.DATA).also { d.readData(lba, 1, it) } }, start)
    private fun le32(b: ByteArray, p: Int) = (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)

    fun music(audio: Regex): List<CdFile> {
        var root: ByteArray? = null; var joliet = false
        for (s in 16 until 32) {
            val v = sector(start + s)
            if (String(v, 1, 5, Charsets.US_ASCII) != "CD001") break
            val type = v[0].toInt() and 0xFF
            if (type == 255) break
            if (type == 1 && root == null) root = v.copyOfRange(156, 190)
            if (type == 2 && v[88].toInt() == 0x25 && v[89].toInt() == 0x2F && (v[90].toInt() in listOf(0x40, 0x43, 0x45))) { root = v.copyOfRange(156, 190); joliet = true }
        }
        val r = root ?: return emptyList()
        val out = mutableListOf<CdFile>()
        fun walk(lba: Int, size: Long, path: String, depth: Int) {
            if (depth > 8 || out.size >= 999) return
            val dirs = mutableListOf<Triple<Int, Long, String>>()
            val sectors = ((size + CdDrive.DATA - 1) / CdDrive.DATA).toInt().coerceAtMost(64)
            for (k in 0 until sectors) {
                val b = sector(lba + k); var p = 0
                while (p < CdDrive.DATA) {
                    val len = b[p].toInt() and 0xFF
                    if (len == 0) break
                    val ext = le32(b, p + 2).toInt(); val sz = le32(b, p + 10); val flags = b[p + 25].toInt(); val nl = b[p + 32].toInt() and 0xFF
                    if (nl == 1 && (b[p + 33].toInt() == 0 || b[p + 33].toInt() == 1)) { p += len; continue }   // . and ..
                    val name = (if (joliet) String(b, p + 33, nl, Charsets.UTF_16BE) else String(b, p + 33, nl, Charsets.ISO_8859_1)).substringBefore(';').trimEnd('.')
                    if (flags and 0x02 != 0) dirs += Triple(ext, sz, name)
                    else if (audio.containsMatchIn(name)) out += CdFile("$path/$name".trimStart('/'), ext, sz)   // extents count from the disc's start
                    p += len
                }
            }
            for ((e, s, n) in dirs.sortedWith(compareBy(Usb.natural) { it.third })) walk(e, s, "$path/$n", depth + 1)
        }
        walk(le32(r, 2).toInt(), le32(r, 10), "", 0)
        return out.sortedWith(compareBy(Usb.natural) { it.path })
    }
}

// cdda://track/3 → the track as a WAV (44-byte header, then the disc's own samples), seekable; cdda://file/12 → a data
// disc's file. Read ahead a few sectors at a time from the drive (the player's loading thread)
@UnstableApi
class CdDataSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var lba = 0; private var total = 0L; private var pos = 0L; private var left = 0L; private var raw = true; private var bodyStart = 0L
    private val buf = ByteArray(24 * CdDrive.RAW); private var bufFrom = -1L; private var bufLen = 0

    override fun open(spec: DataSpec): Long {
        uri = spec.uri; transferInitializing(spec)
        val d = Cd.drive ?: throw IOException("no CD drive")
        val i = spec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("bad CD address")
        if (spec.uri.host == "file") {
            val f = Cd.files.getOrNull(i - 1) ?: throw IOException("no such file")
            raw = false; lba = f.lba; header = ByteArray(0); bodyStart = 0; total = f.size
        } else {
            val t = Cd.tracks.firstOrNull { it.n == i } ?: throw IOException("no such track")
            raw = true; lba = t.lba; val body = t.sectors.toLong() * CdDrive.RAW
            header = wav(body); bodyStart = header.size.toLong(); total = bodyStart + body
        }
        if (d !== Cd.drive) throw IOException("drive changed")
        pos = spec.position; left = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else total - pos
        if (pos > total) throw IOException("past the end")
        bufFrom = -1
        transferStarted(spec)
        return left
    }

    override fun read(out: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        var n = minOf(length.toLong(), left).toInt()
        if (pos < bodyStart) {
            n = minOf(n, (bodyStart - pos).toInt()); System.arraycopy(header, pos.toInt(), out, offset, n)
        } else {
            val size = if (raw) CdDrive.RAW else CdDrive.DATA
            val at = pos - bodyStart
            if (bufFrom < 0 || at < bufFrom || at >= bufFrom + bufLen) fill(at / size * size, size)
            n = minOf(n, (bufFrom + bufLen - at).toInt())
            if (n <= 0) return C.RESULT_END_OF_INPUT
            System.arraycopy(buf, (at - bufFrom).toInt(), out, offset, n)
        }
        pos += n; left -= n; bytesTransferred(n)
        return n
    }

    // the sectors from byte `from` of the body (a whole number of sectors in), as many as fit and are left
    private fun fill(from: Long, size: Int) {
        val d = Cd.drive ?: throw IOException("no CD drive")
        val sector = (from / size).toInt()
        val bodyLen = total - bodyStart
        val count = minOf((buf.size / size).toLong(), (bodyLen - from + size - 1) / size).toInt().coerceAtLeast(1)
        var tries = 0
        while (true) {
            try { if (raw) d.readAudio(lba + sector, count, buf) else d.readData(lba + sector, count, buf); break }
            catch (e: IOException) { if (++tries >= 3) { if (raw) java.util.Arrays.fill(buf, 0, count * size, 0) else throw e; break } }   // a scratch: silence, and on
        }
        bufFrom = from; bufLen = minOf(count.toLong() * size, bodyLen - from).toInt()
    }

    override fun getUri(): Uri? = uri
    override fun close() { if (uri != null) { uri = null; transferEnded() } }

    // 16-bit stereo at 44.1 kHz, little-endian, as on the disc
    private fun wav(body: Long): ByteArray = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + body).toInt()); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(44100); putInt(44100 * 4); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(body.toInt())
    }.array()
}

// the player's sources: cdda:// from the drive, the rest as before (the radio, a pendrive)
@UnstableApi
class CdOrDefault(private val other: DataSource) : DataSource {
    private var cd: CdDataSource? = null
    private var now: DataSource? = null
    override fun addTransferListener(l: TransferListener) { other.addTransferListener(l) }
    override fun open(spec: DataSpec): Long {
        val s = if (spec.uri.scheme == "cdda") (cd ?: CdDataSource().also { cd = it }) else other
        now = s; return s.open(spec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = now?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    override fun getUri(): Uri? = now?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = now?.responseHeaders ?: emptyMap()
    override fun close() { now?.close(); now = null }
    class Factory(private val other: DataSource.Factory) : DataSource.Factory { override fun createDataSource(): DataSource = CdOrDefault(other.createDataSource()) }
}
