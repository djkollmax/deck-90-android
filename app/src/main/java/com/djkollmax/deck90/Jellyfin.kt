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
//  0.1.15: the token goes in the Authorization header (and ApiKey for a song's address): Jellyfin 12 turned the old
//  api_key and X-Emby-Authorization off. A question that gets no answer is asked once more (the Wi-Fi waking up, a
//  connection the phone kept that the network dropped); a list says what went wrong with it, not with an older one.
// ═══════════════════════════════════════
// artistId: the album's artists' ids, "id1,id2" (0.1.15: an album of two artists is under both)
data class JfAlbum(val id: String, val name: String, val artist: String, val year: Int, val artistId: String = "") {
    fun by(artist: String) = artistId == artist || artistId.split(',').contains(artist)
}
data class JfArtist(val id: String, val name: String)
data class JfSong(val id: String, val name: String, val artist: String, val album: String, val albumId: String,
    val n: Int, val disc: Int, val len: Int, val codec: String = "", val kbps: Int = 0, val rate: Int = 0, val bits: Int = 0)

object Jf : MusicLib {
    private const val PREFS = "jellyfin"
    @Volatile var server = ""; private set
    // 0.1.16: the server's other address (its home one, as Jellyfin says it: LocalAddress), tried when the one in use
    // doesn't answer (Tailscale asleep at home, or the home address away from it); the one that answers is kept
    @Volatile var alt = ""; private set
    @Volatile var user = ""; private set                             // the user's id
    @Volatile var name = ""; private set                             // the user's name, as Jellyfin says it
    @Volatile private var token = ""
    @Volatile private var device = ""
    @Volatile override var albums: List<JfAlbum> = emptyList(); private set
    @Volatile override var artists: List<JfArtist> = emptyList(); private set
    @Volatile override var readAt = 0L; private set                           // when the library was last read
    @Volatile override var reading = false; private set
    @Volatile override var error = ""; private set
    @Volatile override var fails = 0; private set                    // 0.1.15: failures so far, so a list knows whether it had one
    private val songs = java.util.concurrent.ConcurrentHashMap<String, List<JfSong>>()   // album id → its songs
    private val byId = java.util.concurrent.ConcurrentHashMap<String, JfSong>()
    @Volatile private var loaded = false
    private lateinit var app: Context

    val signedIn get() = token.isNotEmpty() && user.isNotEmpty() && server.isNotEmpty()
    override val ready get() = signedIn

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true; app = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        server = p.getString("server", "") ?: ""; user = p.getString("user", "") ?: ""; name = p.getString("name", "") ?: ""
        token = p.getString("token", "") ?: ""
        alt = p.getString("alt", "") ?: ""
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
    private fun save() { if (::app.isInitialized) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("server", server).putString("user", user).putString("name", name).putString("token", token).putString("alt", alt).apply() }

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
    class Answered(val code: Int) : IOException("HTTP $code")          // the server answered, with an error

    // 0.1.15: how long a question waits for the connection, and before it's asked again (tests shorten them)
    @Volatile var connectMs = 6000
    @Volatile var againMs = 1500L

    private fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: JSONObject? = null, withToken: Boolean = true,
                     base: String = server, wait: Int = 20_000): Any? {
        // 0.1.15: no answer at all: asked once more (a question that changes something, only when it never reached the server)
        return try {
            try { ask(method, path, query, body, withToken, base, wait) }
            catch (e: IOException) {
                // 0.1.16: the address in use not reached: the other one, kept if it answers
                val other = alt
                if (!unreached(e) || base != server || other.isEmpty() || other == base) throw e
                ask(method, path, query, body, withToken, other, wait).also { swap(base, other) }
            }
        } catch (e: IOException) {
            if (e is Unauthorized || e is Answered || (method != "GET" && e !is java.net.ConnectException && e !is java.net.UnknownHostException)) throw e
            Thread.sleep(againMs)
            ask(method, path, query, body, withToken, base, wait)
        }.also { if (withToken && base == server && error.isNotEmpty() && error != "signed out") { error = ""; Now.changed() } }   // it answers again
    }
    // the connection never made (no answer at the address), not a server that answered slowly or with an error
    fun unreached(e: Exception) = e is java.net.ConnectException || e is java.net.NoRouteToHostException || e is java.net.UnknownHostException ||
        (e is java.net.SocketTimeoutException && (e.message ?: "").contains("connect", ignoreCase = true))
    private fun swap(was: String, now: String) {
        synchronized(this) { if (server != was) return; server = now; alt = was }
        save(); Now.changed()
    }
    // 0.1.16: the server's home address, from the server itself (its public info: no sign-in needed). Blocking, quiet
    fun learn() {
        if (server.isEmpty()) return
        runCatching {
            val o = ask("GET", "/System/Info/Public", emptyMap(), null, false, server, 8000) as? JSONObject ?: return
            val home = clean(o.optString("LocalAddress"))
            if (home.isNotEmpty() && home != server && home != alt) { alt = home; save() }
        }
    }
    // 0.1.16: whether the server answers at its address or the other one (switching to it); for the player's retries
    fun reach(): Boolean {
        if (server.isEmpty()) return false
        for (a in listOf(server, alt).filter { it.isNotEmpty() }.distinct()) {
            val ok = runCatching { ask("GET", "/System/Info/Public", emptyMap(), null, false, a, 8000) }.isSuccess
            if (ok) { if (a != server) swap(server, a); return true }
        }
        return false
    }
    private fun ask(method: String, path: String, query: Map<String, Any?>, body: JSONObject?, withToken: Boolean, base: String, wait: Int): Any? {
        val q = query.filterValues { it != null && it.toString().isNotEmpty() }.entries
            .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value.toString(), "UTF-8") }
        val c = URL(base + path + (if (q.isNotEmpty()) "?$q" else "")).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = connectMs; c.readTimeout = wait
            c.setRequestProperty("Authorization", auth(withToken))
            c.setRequestProperty("Accept", "application/json")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            if (code == 401) throw Unauthorized()
            if (code !in 200..299) throw Answered(code)
            if (code == 204) return null
            val text = c.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) return null
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: org.json.JSONException) { throw IOException("not Jellyfin") }
        finally { runCatching { c.errorStream?.close() } }
    }

    // what to tell the person when the server doesn't answer, in their words
    fun why(e: Exception, s: String = server): String = when (e) {
        is Unauthorized -> "el servidor ya no te reconoce"
        is java.net.SocketTimeoutException -> if (unreached(e)) "no contesta $s: ¿Tailscale está prendido en el celular, o está en el Wi-Fi de casa?"
            else "$s tarda demasiado en contestar: probá de nuevo en un rato"
        is java.net.UnknownHostException -> "no encuentro $s: ¿el celular tiene red?"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "no contesta $s: ¿está prendido el servidor?"
        is Answered -> if (e.code >= 500) "el servidor tuvo un problema (HTTP ${e.code}): probá de nuevo en un rato" else "el servidor no lo tiene (HTTP ${e.code})"
        is javax.net.ssl.SSLException -> "no pude hablar seguro con $s: probá con http://"
        else -> if (e.message == "not Jellyfin") "$s no contestó como Jellyfin" else "se cortó la conexión con $s: probá de nuevo"
    }

    // sign-in: "" when it worked, otherwise what to tell the person (blocking: a thread of its own)
    fun signIn(address: String, userName: String, password: String): String {
        val s = clean(address)
        if (s.isEmpty()) return "La dirección no parece la de un servidor: por ejemplo 192.168.0.199:8096"
        return try {
            val r = call("POST", "/Users/AuthenticateByName", body = JSONObject().put("Username", userName.take(128)).put("Pw", password.take(256)),
                withToken = false, base = s, wait = 30_000) as? JSONObject ?: return "El servidor no contestó como Jellyfin"
            val t = r.optString("AccessToken"); val u = r.optJSONObject("User")
            if (t.isEmpty() || u == null) return "El servidor no contestó como Jellyfin"
            if (s != server) alt = ""
            server = s; token = t; user = u.optString("Id"); name = u.optString("Name").ifEmpty { userName }
            albums = emptyList(); artists = emptyList(); readAt = 0; songs.clear(); byId.clear(); error = ""
            save(); runCatching { cache().delete() }
            learn()
            Now.changed()
            ""
        } catch (e: Unauthorized) { "Usuario o contraseña incorrectos" }
        catch (e: Answered) { if (e.code == 404) "En $s no hay un Jellyfin: ¿es el puerto 8096?" else "No pude entrar: " + why(e, s) }
        catch (e: Exception) { why(e, s).replaceFirstChar { it.uppercase() } }
    }

    fun signOut() {
        runCatching { if (signedIn) call("POST", "/Sessions/Logout") }
        token = ""; user = ""; albums = emptyList(); artists = emptyList(); readAt = 0; songs.clear(); byId.clear()
        save(); runCatching { cache().delete() }; Now.changed()
    }

    // the server no longer takes the token: sign in again
    fun expired() = fail(Unauthorized())
    // 0.1.15: a song's address answered 401: whether the server still takes the token (true), not (false: signed out
    // then), or didn't answer (null). Blocking
    fun check(): Boolean? {
        if (!signedIn) return false
        return try { call("GET", "/Users/$user"); true } catch (e: Unauthorized) { expired(); false } catch (e: Exception) { null }
    }
    private fun fail(e: Exception) {
        error = if (e is Unauthorized) "signed out" else why(e)
        fails++
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
    override fun read(force: Boolean): Boolean {
        if (!signedIn) return false
        if (!force && albums.isNotEmpty() && System.currentTimeMillis() - readAt < 6 * 3600_000L) return true
        // 0.1.11: being read already (MODE just asked for it): wait for that reading instead of answering an empty list
        // (0.1.15: up to three minutes, a big library's first reading)
        var waiting = false
        synchronized(this) {
            if (reading) waiting = true else reading = true
        }
        if (waiting) { val t0 = System.currentTimeMillis(); while (reading && albums.isEmpty() && System.currentTimeMillis() - t0 < 180_000) Thread.sleep(100); return albums.isNotEmpty() }
        Now.changed()
        try {
            val al = all("/Users/$user/Items", mapOf("IncludeItemTypes" to "MusicAlbum", "Recursive" to true, "SortBy" to "SortName", "Fields" to "AlbumArtist,ProductionYear"))
                .map { JfAlbum(it.optString("Id"), it.optString("Name").ifEmpty { "UNTITLED" }, it.optString("AlbumArtist"), it.optInt("ProductionYear"), artistIds(it)) }
            val ar = all("/Artists/AlbumArtists", mapOf("UserId" to user, "SortBy" to "SortName"))
                .map { JfArtist(it.optString("Id"), it.optString("Name").ifEmpty { "?" }) }
            albums = al.sortedBy { sortKey(it.name) }; artists = ar.sortedBy { sortKey(it.name) }; readAt = System.currentTimeMillis(); error = ""
            learn()
            runCatching { cache().writeText(JSONObject().put("server", server).put("user", user).put("at", readAt)
                .put("albums", JSONArray(albums.map { JSONArray().put(it.id).put(it.name).put(it.artist).put(it.year).put(it.artistId) }))
                .put("artists", JSONArray(artists.map { JSONArray().put(it.id).put(it.name) })).toString()) }
            return true
        } catch (e: Exception) { fail(e); return albums.isNotEmpty() }
        finally { reading = false; Now.changed() }
    }

    // an album's songs, in the order of the disc (blocking; kept once read)
    override fun songsOf(albumId: String): List<JfSong> {
        if (Tel.mine(albumId)) return Tel.songsOf(albumId)
        songs[albumId]?.let { return it }
        if (!signedIn || !ID.matches(albumId)) return emptyList()
        return try {
            val r = call("GET", "/Users/$user/Items", mapOf("ParentId" to albumId, "IncludeItemTypes" to "Audio", "Recursive" to true,
                "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "Fields" to "MediaSources")) as? JSONObject
            val list = songsFrom(r?.optJSONArray("Items") ?: JSONArray())
            songs[albumId] = list; list.forEach { byId[it.id] = it }; list
        } catch (e: Exception) { fail(e); emptyList() }
    }
    override fun albumsOf(artistId: String): List<JfAlbum> {
        if (Tel.mine(artistId)) return Tel.albumsOf(artistId)
        val mine = albums.filter { it.by(artistId) }
        if (mine.isNotEmpty() || !signedIn) return mine
        return try {
            val r = call("GET", "/Users/$user/Items", mapOf("AlbumArtistIds" to artistId, "IncludeItemTypes" to "MusicAlbum", "Recursive" to true,
                "SortBy" to "ProductionYear,SortName", "Fields" to "AlbumArtist,ProductionYear")) as? JSONObject
            val a = r?.optJSONArray("Items") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { JfAlbum(it.optString("Id"), it.optString("Name"), it.optString("AlbumArtist"), it.optInt("ProductionYear"), artistIds(it).ifEmpty { artistId }) }
        } catch (e: Exception) { fail(e); emptyList() }
    }
    private fun artistIds(o: JSONObject): String = o.optJSONArray("AlbumArtists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("Id")?.takeIf { id -> id.isNotEmpty() && !id.contains(',') } } }
        .orEmpty().joinToString(",")
    // the newest albums on the server
    override fun recent(): List<JfAlbum> = if (!signedIn) emptyList() else try {
        val r = call("GET", "/Users/$user/Items", mapOf("IncludeItemTypes" to "MusicAlbum", "Recursive" to true, "SortBy" to "DateCreated",
            "SortOrder" to "Descending", "Limit" to 60, "Fields" to "AlbumArtist,ProductionYear")) as? JSONObject
        val a = r?.optJSONArray("Items") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { JfAlbum(it.optString("Id"), it.optString("Name"), it.optString("AlbumArtist"), it.optInt("ProductionYear")) }
    } catch (e: Exception) { fail(e); emptyList() }
    // songs from the whole library at random (AL AZAR)
    override fun shuffle(count: Int): List<JfSong> = if (!signedIn) emptyList() else try {
        val r = call("GET", "/Users/$user/Items", mapOf("IncludeItemTypes" to "Audio", "Recursive" to true, "SortBy" to "Random", "Limit" to count,
            "Fields" to "MediaSources")) as? JSONObject
        songsFrom(r?.optJSONArray("Items") ?: JSONArray()).also { l -> l.forEach { byId[it.id] = it } }
    } catch (e: Exception) { fail(e); emptyList() }
    // any words in the song, the album or the artist: artists and albums from what's kept, songs from the server
    override fun search(text: String): Triple<List<JfArtist>, List<JfAlbum>, List<JfSong>> {
        val words = sortKey(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val hit = { s: String -> val k = sortKey(s); words.all { k.contains(it) } }
        val ar = artists.filter { hit(it.name) }.take(30)
        val al = albums.filter { hit(it.name + " " + it.artist) }.take(60)
        val so = if (!signedIn) emptyList() else try {
            val r = call("GET", "/Users/$user/Items", mapOf("SearchTerm" to text.trim().take(100), "IncludeItemTypes" to "Audio", "Recursive" to true,
                "Limit" to 60, "Fields" to "MediaSources")) as? JSONObject
            songsFrom(r?.optJSONArray("Items") ?: JSONArray()).also { l -> l.forEach { byId[it.id] = it } }
        } catch (e: Exception) { emptyList() }
        return Triple(ar, al, so)
    }
    fun song(id: String): JfSong? = byId[id]
    // 0.1.10: TEL's songs, known by their ids as the server's are
    fun know(list: List<JfSong>) { list.forEach { byId[it.id] = it } }

    private val ID = Regex("^([0-9a-fA-F-]{1,64}|t\\d{1,19})$")             // the server's, or TEL's ("t1234", Tel.kt)
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
        if (Tel.mine(id)) return Tel.stream(id)                       // 0.1.10: a song on the phone
        if (!signedIn || !ID.matches(id)) throw IOException("signed out")
        return "$server/Audio/$id/universal?UserId=$user&DeviceId=$device&MaxStreamingBitrate=140000000" +
            "&Container=flac,mp3,aac,m4a%7Caac,m4b%7Caac,ogg,oga,opus,webma,wav&TranscodingContainer=flac&TranscodingProtocol=http" +
            "&AudioCodec=flac&ApiKey=$token"
    }
    // 0.1.15: and the header that carries the token (what Jellyfin asks for; ApiKey above if a redirect drops it)
    fun streamHeaders(id: String): Map<String, String> = if (Tel.mine(id) || !signedIn) emptyMap() else mapOf("Authorization" to auth())
    // an album's picture (Jellyfin serves pictures without a token)
    fun cover(albumId: String): Uri? = if (Tel.mine(albumId)) Tel.cover(albumId) else if (signedIn && ID.matches(albumId)) Uri.parse("$server/Items/$albumId/Images/Primary?maxHeight=300&quality=85") else null

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
