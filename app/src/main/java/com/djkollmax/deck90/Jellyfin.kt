package com.djkollmax.deck90

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID

// ═══════════════════════════════════════
//  SERVER: the person's Jellyfin music, as the DM-90 has it on the PC (deck-90's electron/jellyfin.js). The server,
//  user and password are typed on the phone once; Jellyfin answers with an access token, and that is what's kept
//  (the app's own private settings), never the password. Music libraries only, all of them together.
//  The library's albums and artists are read once (pages of 2,000, no pictures) and kept in a file, so the car and
//  the face have them at once; an album's songs when it's opened. A song plays from deck90://jf/<id>, which the
//  player's resolver (RadioService.Resolver) turns into the server's address with the token: the token never goes
//  in what the car or the notification see. Jellyfin decides per song: the file as it is when the phone plays its
//  format (FLAC, MP3, AAC, Opus, Vorbis, WAV), or made FLAC on the server when it doesn't (ALAC, WMA, APE, DSD…).
// ═══════════════════════════════════════
data class JfAlbum(val id: String, val name: String, val artist: String, val year: Int, val artistId: String = "")
data class JfArtist(val id: String, val name: String)
data class JfSong(val id: String, val name: String, val artist: String, val album: String, val albumId: String,
    val n: Int, val disc: Int, val len: Int, val codec: String = "", val kbps: Int = 0, val rate: Int = 0, val bits: Int = 0)

object Jf {
    private const val PREFS = "jellyfin"
    @Volatile var server = ""; private set
    @Volatile var user = ""; private set                             // the user's id
    @Volatile var name = ""; private set                             // the user's name, as Jellyfin says it
    @Volatile private var token = ""
    @Volatile private var device = ""
    @Volatile var albums: List<JfAlbum> = emptyList(); private set
    @Volatile var artists: List<JfArtist> = emptyList(); private set
    @Volatile var readAt = 0L; private set                           // when the library was last read
    @Volatile var reading = false; private set
    @Volatile var error = ""; private set
    private val songs = java.util.concurrent.ConcurrentHashMap<String, List<JfSong>>()   // album id → its songs
    private val byId = java.util.concurrent.ConcurrentHashMap<String, JfSong>()
    @Volatile private var loaded = false
    private lateinit var app: Context

    val signedIn get() = token.isNotEmpty() && user.isNotEmpty() && server.isNotEmpty()

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true; app = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        server = p.getString("server", "") ?: ""; user = p.getString("user", "") ?: ""; name = p.getString("name", "") ?: ""
        token = p.getString("token", "") ?: ""
        device = p.getString("device", "") ?: ""
        if (device.isEmpty()) { device = "deck90-android-" + UUID.randomUUID().toString().take(12); p.edit().putString("device", device).apply() }
        runCatching {
            val o = JSONObject(cache().readText())
            if (o.optString("server") == server && o.optString("user") == user) {
                albums = albumsFrom(o.optJSONArray("albums") ?: JSONArray()); artists = artistsFrom(o.optJSONArray("artists") ?: JSONArray())
                readAt = o.optLong("at")
            }
        }
    }
    private fun cache() = File(app.filesDir, "jellyfin-library.json")
    private fun save() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("server", server).putString("user", user).putString("name", name).putString("token", token).apply()

    // "http://192.168.0.199:8096", "100.101.102.103:8096", "musica.casa.com": an address Jellyfin answers on
    fun clean(s: String): String {
        var t = s.trim().trimEnd('/')
        if (t.isEmpty()) return ""
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t = "http://$t"
        return if (Regex("^https?://[^\\s/?#]+(:\\d+)?(/[^\\s?#]*)?$", RegexOption.IGNORE_CASE).matches(t)) t else ""
    }

    private fun auth(withToken: Boolean = true) = "MediaBrowser Client=\"Deck 90 Android\", Device=\"Deck 90\", DeviceId=\"$device\", Version=\"" +
        BuildConfigInfo.version + "\"" + (if (withToken && token.isNotEmpty()) ", Token=\"$token\"" else "")

    class Unauthorized : IOException("unauthorized")

    private fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: JSONObject? = null, withToken: Boolean = true,
                     base: String = server, wait: Int = 20_000): Any? {
        val q = query.filterValues { it != null && it.toString().isNotEmpty() }.entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value.toString(), "UTF-8") }
        val c = URL(base + path + (if (q.isNotEmpty()) "?$q" else "")).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = wait
        c.setRequestProperty("Authorization", auth(withToken)); c.setRequestProperty("X-Emby-Authorization", auth(withToken))
        c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        if (code == 401) throw Unauthorized()
        if (code !in 200..299) throw IOException("HTTP $code")
        if (code == 204) return null
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    // sign-in: "" when it worked, otherwise what to tell the person (blocking: a thread of its own)
    fun signIn(address: String, userName: String, password: String): String {
        val s = clean(address)
        if (s.isEmpty()) return "La dirección no parece la de un servidor: por ejemplo 192.168.0.199:8096"
        return try {
            val r = call("POST", "/Users/AuthenticateByName", body = JSONObject().put("Username", userName.take(128)).put("Pw", password.take(256)),
                withToken = false, base = s) as? JSONObject ?: return "El servidor no contestó como Jellyfin"
            val t = r.optString("AccessToken"); val u = r.optJSONObject("User")
            if (t.isEmpty() || u == null) return "El servidor no contestó como Jellyfin"
            server = s; token = t; user = u.optString("Id"); name = u.optString("Name").ifEmpty { userName }
            albums = emptyList(); artists = emptyList(); readAt = 0; songs.clear(); byId.clear(); error = ""
            save(); runCatching { cache().delete() }
            Now.changed()
            ""
        } catch (e: Unauthorized) { "Usuario o contraseña incorrectos" }
        catch (e: java.net.SocketTimeoutException) { "No contesta $s: ¿el celular está en la misma red o en Tailscale?" }
        catch (e: java.net.UnknownHostException) { "No encuentro $s" }
        catch (e: java.net.ConnectException) { "No contesta $s: ¿está prendido el servidor?" }
        catch (e: Exception) { "No pude entrar: " + (e.message ?: e.javaClass.simpleName) }
    }

    fun signOut() {
        runCatching { if (signedIn) call("POST", "/Sessions/Logout") }
        token = ""; user = ""; albums = emptyList(); artists = emptyList(); readAt = 0; songs.clear(); byId.clear()
        save(); runCatching { cache().delete() }; Now.changed()
    }

    // the server no longer takes the token (a song's address answered 401): sign in again
    fun expired() = fail(Unauthorized())
    private fun fail(e: Exception) {
        error = if (e is Unauthorized) "signed out" else e.message ?: e.javaClass.simpleName
        if (e is Unauthorized) { token = ""; save() }                // the server no longer takes it: sign in again
        Now.changed()
    }

    private fun libraries(): List<String> {
        val v = call("GET", "/Users/$user/Views") as? JSONObject ?: return emptyList()
        val a = v.optJSONArray("Items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("CollectionType") == "music" }.map { it.optString("Id") }
    }

    // every item of a kind in the music libraries, in pages of 2,000 with no pictures, each id once
    private fun all(path: String, query: Map<String, Any?>): List<JSONObject> {
        val out = mutableListOf<JSONObject>(); val seen = HashSet<String>()
        for (lib in libraries().ifEmpty { listOf("") }) {
            var start = 0
            while (true) {
                val r = call("GET", path, query + mapOf("ParentId" to lib, "StartIndex" to start, "Limit" to 2000, "EnableImages" to false,
                    "EnableUserData" to false, "ImageTypeLimit" to 0), wait = 60_000) as? JSONObject ?: break
                val items = r.optJSONArray("Items") ?: break
                for (i in 0 until items.length()) items.optJSONObject(i)?.let { if (seen.add(it.optString("Id"))) out += it }
                start += items.length()
                if (items.length() < 2000 || start >= r.optInt("TotalRecordCount")) break
            }
        }
        return out
    }

    // the albums and artists read (again): blocking. False when the server didn't answer
    fun read(force: Boolean = false): Boolean {
        if (!signedIn) return false
        if (!force && albums.isNotEmpty() && System.currentTimeMillis() - readAt < 6 * 3600_000L) return true
        if (reading) return albums.isNotEmpty()
        reading = true; Now.changed()
        try {
            val al = all("/Users/$user/Items", mapOf("IncludeItemTypes" to "MusicAlbum", "Recursive" to true, "SortBy" to "SortName", "Fields" to "AlbumArtist,ProductionYear"))
                .map { JfAlbum(it.optString("Id"), it.optString("Name").ifEmpty { "UNTITLED" }, it.optString("AlbumArtist"), it.optInt("ProductionYear"),
                    it.optJSONArray("AlbumArtists")?.optJSONObject(0)?.optString("Id").orEmpty()) }
            val ar = all("/Artists/AlbumArtists", mapOf("UserId" to user, "SortBy" to "SortName"))
                .map { JfArtist(it.optString("Id"), it.optString("Name").ifEmpty { "?" }) }
            albums = al.sortedBy { sortKey(it.name) }; artists = ar.sortedBy { sortKey(it.name) }; readAt = System.currentTimeMillis(); error = ""
            runCatching { cache().writeText(JSONObject().put("server", server).put("user", user).put("at", readAt)
                .put("albums", JSONArray(albums.map { JSONArray().put(it.id).put(it.name).put(it.artist).put(it.year).put(it.artistId) }))
                .put("artists", JSONArray(artists.map { JSONArray().put(it.id).put(it.name) })).toString()) }
            return true
        } catch (e: Exception) { fail(e); return albums.isNotEmpty() }
        finally { reading = false; Now.changed() }
    }

    // an album's songs, in the order of the disc (blocking; kept once read)
    fun songsOf(albumId: String): List<JfSong> {
        songs[albumId]?.let { return it }
        if (!signedIn || !ID.matches(albumId)) return emptyList()
        return try {
            val r = call("GET", "/Users/$user/Items", mapOf("ParentId" to albumId, "IncludeItemTypes" to "Audio", "Recursive" to true,
                "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "Fields" to "MediaSources")) as? JSONObject
            val list = songsFrom(r?.optJSONArray("Items") ?: JSONArray())
            songs[albumId] = list; list.forEach { byId[it.id] = it }; list
        } catch (e: Exception) { fail(e); emptyList() }
    }
    fun albumsOf(artistId: String): List<JfAlbum> {
        val mine = albums.filter { it.artistId == artistId }
        if (mine.isNotEmpty() || !signedIn) return mine
        return try {
            val r = call("GET", "/Users/$user/Items", mapOf("AlbumArtistIds" to artistId, "IncludeItemTypes" to "MusicAlbum", "Recursive" to true,
                "SortBy" to "ProductionYear,SortName", "Fields" to "AlbumArtist,ProductionYear")) as? JSONObject
            val a = r?.optJSONArray("Items") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { JfAlbum(it.optString("Id"), it.optString("Name"), it.optString("AlbumArtist"), it.optInt("ProductionYear"), artistId) }
        } catch (e: Exception) { fail(e); emptyList() }
    }
    // the newest albums on the server
    fun recent(): List<JfAlbum> = try {
        val r = call("GET", "/Users/$user/Items", mapOf("IncludeItemTypes" to "MusicAlbum", "Recursive" to true, "SortBy" to "DateCreated",
            "SortOrder" to "Descending", "Limit" to 60, "Fields" to "AlbumArtist,ProductionYear")) as? JSONObject
        val a = r?.optJSONArray("Items") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { JfAlbum(it.optString("Id"), it.optString("Name"), it.optString("AlbumArtist"), it.optInt("ProductionYear")) }
    } catch (e: Exception) { fail(e); emptyList() }
    // songs from the whole library at random (AL AZAR)
    fun shuffle(count: Int = 100): List<JfSong> = try {
        val r = call("GET", "/Users/$user/Items", mapOf("IncludeItemTypes" to "Audio", "Recursive" to true, "SortBy" to "Random", "Limit" to count,
            "Fields" to "MediaSources")) as? JSONObject
        songsFrom(r?.optJSONArray("Items") ?: JSONArray()).also { l -> l.forEach { byId[it.id] = it } }
    } catch (e: Exception) { fail(e); emptyList() }
    // any words in the song, the album or the artist: artists and albums from what's kept, songs from the server
    fun search(text: String): Triple<List<JfArtist>, List<JfAlbum>, List<JfSong>> {
        val words = sortKey(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val hit = { s: String -> val k = sortKey(s); words.all { k.contains(it) } }
        val ar = artists.filter { hit(it.name) }.take(30)
        val al = albums.filter { hit(it.name + " " + it.artist) }.take(60)
        val so = try {
            val r = call("GET", "/Users/$user/Items", mapOf("SearchTerm" to text.trim().take(100), "IncludeItemTypes" to "Audio", "Recursive" to true,
                "Limit" to 60, "Fields" to "MediaSources")) as? JSONObject
            songsFrom(r?.optJSONArray("Items") ?: JSONArray()).also { l -> l.forEach { byId[it.id] = it } }
        } catch (e: Exception) { emptyList() }
        return Triple(ar, al, so)
    }
    fun song(id: String): JfSong? = byId[id]

    private val ID = Regex("^[0-9a-fA-F-]{1,64}$")
    fun songsFrom(a: JSONArray): List<JfSong> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { i ->
        val src = i.optJSONArray("MediaSources")?.optJSONObject(0)
        val st = src?.optJSONArray("MediaStreams")?.let { s -> (0 until s.length()).mapNotNull { s.optJSONObject(it) }.firstOrNull { it.optString("Type") == "Audio" } }
        // 0.1.10: who sings it (a compilation's song said "Various Artists"), else the album's artist
        val performers = i.optJSONArray("Artists")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        JfSong(i.optString("Id"), i.optString("Name").ifEmpty { "UNTITLED" }, performers.joinToString(", ").ifEmpty { i.optString("AlbumArtist") },
            i.optString("Album"), i.optString("AlbumId"), i.optInt("IndexNumber"), i.optInt("ParentIndexNumber"), (i.optLong("RunTimeTicks") / 10_000_000L).toInt(),
            st?.optString("Codec").orEmpty().lowercase(), ((st?.optInt("BitRate") ?: 0).takeIf { it > 0 } ?: src?.optInt("Bitrate") ?: 0) / 1000,
            st?.optInt("SampleRate") ?: 0, st?.optInt("BitDepth") ?: 0)
    }
    private fun albumsFrom(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { JfAlbum(it.optString(0), it.optString(1), it.optString(2), it.optInt(3), it.optString(4)) }
    private fun artistsFrom(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { JfArtist(it.optString(0), it.optString(1)) }

    // names as a person reads them: no accents, case or a leading article in the way (as the DM-90)
    fun sortKey(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("^(the|los|las|el|la)\\s+"), "").trim()
    // the letter an album or artist files under on the car: A–Z, or # for the rest
    fun letter(s: String): String = sortKey(s).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

    // the address a song's sound comes from, with the token: only for the player (RadioService.Resolver)
    fun stream(id: String): String {
        if (!signedIn || !ID.matches(id)) throw IOException("signed out")
        return "$server/Audio/$id/universal?UserId=$user&DeviceId=$device&MaxStreamingBitrate=140000000" +
            "&Container=flac,mp3,aac,m4a%7Caac,m4b%7Caac,ogg,oga,opus,webma,wav&TranscodingContainer=flac&TranscodingProtocol=http" +
            "&AudioCodec=flac&api_key=$token"
    }
    // an album's picture (Jellyfin serves pictures without a token)
    fun cover(albumId: String): Uri? = if (signedIn && ID.matches(albumId)) Uri.parse("$server/Items/$albumId/Images/Primary?maxHeight=300&quality=85") else null

    // the player's items and the car's
    fun item(s: JfSong, playlist: String = ""): MediaItem = MediaItem.Builder().setMediaId("jf:${s.id}").setUri("deck90://jf/${s.id}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(s.name).setArtist(s.artist).setAlbumTitle(s.album).setAlbumArtist(s.artist)
            .setTrackNumber(s.n.takeIf { it > 0 }).setArtworkUri(cover(s.albumId))
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(android.os.Bundle().apply { putString("album", s.albumId); putString("list", playlist); putInt("len", s.len)
                putString("codec", s.codec); putInt("kbps", s.kbps); putInt("rate", s.rate); putInt("bits", s.bits) }).build())
        .build()
    fun albumItem(a: JfAlbum): MediaItem = MediaItem.Builder().setMediaId("jfalbum:${a.id}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(a.name).setSubtitle(listOf(a.artist, if (a.year > 0) a.year.toString() else "").filter { it.isNotEmpty() }.joinToString(" · "))
            .setArtist(a.artist).setArtworkUri(cover(a.id)).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM).build()).build()
    fun artistItem(a: JfArtist): MediaItem = MediaItem.Builder().setMediaId("jfartist:${a.id}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(a.name).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST).build()).build()

    // the quality a song plays at, as the display shows it: FLAC 24/96, MP3 320
    fun quality(s: JfSong?): String {
        if (s == null) return ""
        val c = when (s.codec) { "flac" -> "FLAC"; "alac" -> "ALAC"; "mp3" -> "MP3"; "aac" -> "AAC"; "opus" -> "OPUS"; "vorbis" -> "OGG"
            "pcm_s16le", "pcm_s24le", "pcm_s32le" -> "WAV"; "" -> ""; else -> s.codec.uppercase().take(5) }
        val lossless = c in setOf("FLAC", "ALAC", "WAV")
        val khz = if (s.rate > 0) (if (s.rate % 1000 == 0) (s.rate / 1000).toString() else "%.1f".format(java.util.Locale.ROOT, s.rate / 1000.0)) else ""
        return when {
            lossless && s.bits > 0 && khz.isNotEmpty() -> "$c ${s.bits}/$khz"
            !lossless && s.kbps > 0 -> "$c ${s.kbps}"
            else -> c
        }
    }

    // the songs playing, kept for the car's resumption (Store.jfQueue): as JSON, and back
    fun queueJson(list: List<JfSong>): String = JSONArray(list.take(500).map { JSONArray().put(it.id).put(it.name).put(it.artist).put(it.album).put(it.albumId)
        .put(it.n).put(it.disc).put(it.len).put(it.codec).put(it.kbps).put(it.rate).put(it.bits) }).toString()
    fun queueFrom(text: String): List<JfSong> = runCatching { JSONArray(text) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map {
        JfSong(it.optString(0), it.optString(1), it.optString(2), it.optString(3), it.optString(4), it.optInt(5), it.optInt(6), it.optInt(7),
            it.optString(8), it.optInt(9), it.optInt(10), it.optInt(11)).also { s -> byId[s.id] = s } } } ?: emptyList()
    fun parse(id: String?): String? = id?.removePrefix("jf:")?.takeIf { id.startsWith("jf:") && ID.matches(it) }
}
