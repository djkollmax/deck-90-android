package com.djkollmax.deck90

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

// 0.1.15: SERVER against a pretend Jellyfin on the computer: signing in, the library, a list after a dropped
// connection, errors that belong to the list that had them, and the token where Jellyfin 12 wants it
class JellyfinServerTest {
    private lateinit var http: ServerSocket
    private lateinit var base: String
    private val seen = mutableListOf<Pair<String, Map<String, String>>>()   // path, headers
    private val drop = AtomicInteger(0)                                     // connections to cut before answering
    @Volatile private var down = false                                      // answer 500 to everything
    @Volatile private var revoked = false                                   // the token no longer taken
    @Volatile private var home = ""                                         // the LocalAddress the server tells
    private val prefs = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val others = mutableListOf<ServerSocket>()

    private val albums = """{"Items":[
        {"Id":"a1","Name":"Immunity","AlbumArtist":"Jon Hopkins","ProductionYear":2013,"AlbumArtists":[{"Id":"r1","Name":"Jon Hopkins"}]},
        {"Id":"a2","Name":"Singularity","AlbumArtist":"Jon Hopkins","ProductionYear":2018,"AlbumArtists":[{"Id":"r1","Name":"Jon Hopkins"}]},
        {"Id":"a3","Name":"Sin documentos","AlbumArtist":"Los Rodríguez, Andrés Calamaro","ProductionYear":1993,
         "AlbumArtists":[{"Id":"r2","Name":"Los Rodríguez"},{"Id":"r3","Name":"Andrés Calamaro"}]}],"TotalRecordCount":3}"""
    private val artists = """{"Items":[{"Id":"r1","Name":"Jon Hopkins"},{"Id":"r2","Name":"Los Rodríguez"},{"Id":"r3","Name":"Andrés Calamaro"}],"TotalRecordCount":3}"""
    private val songs = """{"Items":[
        {"Id":"b1","Name":"We Disappear","AlbumArtist":"Jon Hopkins","Album":"Immunity","AlbumId":"a1","IndexNumber":1,"RunTimeTicks":2720000000},
        {"Id":"b2","Name":"Open Eye Signal","AlbumArtist":"Jon Hopkins","Album":"Immunity","AlbumId":"a1","IndexNumber":2,"RunTimeTicks":4700000000}]}"""

    // one request on a connection (a small HTTP/1.1 server: Android's tests have no JDK one)
    private fun answer(sock: Socket) { sock.use { serve(it) } }
    private fun serve(sock: Socket) {
        val inp = sock.getInputStream()
        fun line(): String { val b = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
        val first = line().split(' '); if (first.size < 2) return
        val headers = HashMap<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
        val sent = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0).also { var got = 0; while (got < it.size) { val n = inp.read(it, got, it.size - got); if (n < 0) break; got += n } }
        val path = first[1].substringBefore('?'); val q = first[1].substringAfter('?', ""); val method = first[0]
        synchronized(seen) { seen += path to headers }
        if (drop.getAndDecrement() > 0) return                                 // the connection cut: no answer at all
        drop.compareAndSet(-1, 0)
        val auth = headers["authorization"].orEmpty()
        val (code, body) = when {
            down -> 500 to ""
            path == "/System/Info/Public" -> 200 to """{"ServerName":"max-3090","LocalAddress":"$home","Version":"10.11.3"}"""
            path == "/Users/AuthenticateByName" -> {
                if (String(sent).contains("\"Pw\":\"bien\"")) 200 to """{"AccessToken":"tok123","User":{"Id":"u1","Name":"Damián"}}""" else 401 to ""
            }
            !path.startsWith("/Users/") && !path.startsWith("/Artists/") && !path.startsWith("/Playlists/") && !path.startsWith("/DisplayPreferences/") -> 404 to ""        // not Jellyfin's
            !auth.contains("Token=\"tok123\"") || revoked -> 401 to ""
            path == "/Users/u1" -> 200 to """{"Id":"u1","Name":"Damián"}"""
            path == "/Users/u1/Views" -> 200 to """{"Items":[{"Id":"lib1","CollectionType":"music"},{"Id":"lib2","CollectionType":"movies"}]}"""
            // 0.1.20: a playlist the PC made, and the place kept for it (the user's display preferences for "deck90")
            path == "/Users/u1/Items" && q.contains("IncludeItemTypes=Playlist") -> 200 to """{"Items":[{"Id":"f1000001","Name":"Ruta","Type":"Playlist","MediaType":"Audio","ChildCount":2}]}"""
            path == "/Playlists/f1000001/Items" -> 200 to songs
            path == "/DisplayPreferences/deck90" && method == "GET" -> 200 to JSONObject().put("Id", "deck90").put("Client", "deck90").put("CustomPrefs", JSONObject(prefs as Map<*, *>)).toString()
            path == "/DisplayPreferences/deck90" -> { JSONObject(String(sent)).optJSONObject("CustomPrefs")?.let { c -> c.keys().forEach { k -> prefs[k] = c.getString(k) } }; 204 to "" }
            path == "/Users/u1/Items" && q.contains("IncludeItemTypes=MusicAlbum") -> 200 to albums
            path == "/Users/u1/Items" && q.contains("IncludeItemTypes=Audio") && q.contains("ParentId=a1") -> 200 to songs
            path == "/Artists/AlbumArtists" -> 200 to artists
            else -> 404 to ""
        }
        val b = body.toByteArray()
        sock.getOutputStream().apply {
            write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray()); write(b); flush()
        }
    }

    @Before fun start() {
        http = listen()
        base = "127.0.0.1:" + http.localPort
        Jf.connectMs = 2000; Jf.againMs = 50
    }
    // the same pretend server at another address (its home one)
    private fun listen(): ServerSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also { sock ->
        Thread { while (!sock.isClosed) { val c = runCatching { sock.accept() }.getOrNull() ?: break; Thread { runCatching { answer(c) } }.start() } }.apply { isDaemon = true }.start()
    }
    @After fun stop() { http.close(); others.forEach { it.close() }; home = "" }

    // 0.1.16: Tailscale asleep (the address in use doesn't answer): the home address the server told is tried, and kept
    @Test fun `the server's other address when the one in use doesn't answer`() {
        val other = listen().also { others += it }
        home = "http://127.0.0.1:" + other.localPort
        signedIn()
        assertEquals(home, Jf.alt)
        Jf.read(force = true)
        http.close()                                                           // the address signed in with: gone
        // (the JDK may keep listening until its accept thread wakes: wait until the port really refuses)
        repeat(50) { if (runCatching { Socket("127.0.0.1", http.localPort).close() }.isFailure) return@repeat; Thread.sleep(50) }
        val was = Jf.server
        assertEquals(2, Jf.songsOf("a1").size)
        assertEquals("was $was", home, Jf.server); assertEquals("http://$base", Jf.alt)
        assertTrue(Jf.stream("b1").startsWith(home + "/Audio/b1/"))
        assertTrue(Jf.reach())
    }

    private fun signedIn() { assertEquals("", Jf.signIn(base, "damian", "bien")); synchronized(seen) { seen.clear() } }

    @Test fun `signing in keeps the token, and says what's wrong when it can't`() {
        assertEquals("Usuario o contraseña incorrectos", Jf.signIn(base, "damian", "mal"))
        assertEquals("", Jf.signIn(base, "damian", "bien"))
        assertTrue(Jf.signedIn); assertEquals("Damián", Jf.name); assertEquals("http://$base", Jf.server)
        Jf.signIn("$base/otra-cosa", "damian", "bien").let { assertTrue(it, it.startsWith("En http://$base/otra-cosa no hay un Jellyfin")) }
        val closed = ServerSocket(0).use { it.localPort }                      // a port nothing answers on
        Jf.signIn("127.0.0.1:$closed", "damian", "bien").let { assertTrue(it, it.endsWith("¿está prendido el servidor?")) }
        Jf.signIn("no es una dirección", "damian", "bien").let { assertTrue(it, it.startsWith("La dirección no parece")) }
        assertTrue("a failed sign-in elsewhere keeps the one there was", Jf.signedIn && Jf.server == "http://$base")
    }

    @Test fun `the token goes in the Authorization header, not in the old ones`() {
        signedIn()
        assertTrue(Jf.read(force = true))
        synchronized(seen) {
            assertTrue(seen.isNotEmpty())
            for ((path, h) in seen.filter { it.first != "/System/Info/Public" }) {   // public: no sign-in
                assertTrue(path, h["authorization"].orEmpty().contains("Token=\"tok123\""))
                assertFalse(path, h.containsKey("x-emby-authorization") || h.containsKey("x-emby-token"))
            }
        }
        val url = Jf.stream("b1")
        assertTrue(url.contains("ApiKey=tok123")); assertFalse(url.contains("api_key"))
        assertTrue(Jf.streamHeaders("b1")["Authorization"].orEmpty().contains("Token=\"tok123\""))
        assertEquals(emptyMap<String, String>(), Jf.streamHeaders("t42"))   // a song on the phone needs none
    }

    @Test fun `the library, and an album of two artists under both`() {
        signedIn()
        assertTrue(Jf.read(force = true))
        assertEquals(listOf("Immunity", "Sin documentos", "Singularity"), Jf.albums.map { it.name })
        assertEquals(listOf("Sin documentos"), Jf.albumsOf("r3").map { it.name })
        assertEquals(listOf("Sin documentos"), Jf.albumsOf("r2").map { it.name })
        assertEquals(listOf("We Disappear", "Open Eye Signal"), Jf.songsOf("a1").map { it.name })
    }

    @Test fun `a connection cut once is asked again`() {
        signedIn(); Jf.read(force = true)
        drop.set(1)
        assertEquals(2, Jf.songsOf("a1").size)
        assertEquals("", Jf.error)
    }

    @Test fun `a failure is told once and goes when the server answers again`() {
        signedIn(); Jf.read(force = true)
        down = true
        val f0 = Jf.fails
        assertEquals(emptyList<JfAlbum>(), Jf.recent())
        assertTrue(Jf.fails > f0)
        assertTrue(Jf.error, Jf.error.startsWith("el servidor tuvo un problema (HTTP 500)"))
        down = false
        val f1 = Jf.fails
        assertEquals(2, Jf.songsOf("a1").size)
        assertEquals("a list that worked has no failure of its own", f1, Jf.fails)
        assertEquals("", Jf.error)
        assertTrue(Jf.signedIn)
    }

    @Test fun `a song's 401 signs out only when the server says so for the person too`() {
        signedIn()
        assertEquals(true, Jf.check()); assertTrue(Jf.signedIn)
        down = true
        assertEquals(null, Jf.check()); assertTrue("no answer isn't a reason to sign out", Jf.signedIn)
        down = false; revoked = true
        assertEquals(false, Jf.check()); assertFalse(Jf.signedIn)
        revoked = false
    }

    // 0.1.20: the PC's lists are here, and where one was left goes both ways
    @Test fun `the lists are Jellyfin's, and where one was left goes both ways`() {
        signedIn()
        assertEquals(listOf("Ruta" to 2), Jf.lists().map { it.name to it.count })
        assertEquals(listOf("We Disappear", "Open Eye Signal"), Jf.listSongs("f1000001").map { it.name })
        prefs["list:f1000001"] = """{"song":"b2","i":1,"t":95,"at":1}"""          // left on the PC on the second song, 1:35 in
        val p = Jf.place("f1000001")!!
        assertEquals("b2", p.getString("song")); assertEquals(95L, p.getLong("t"))
        Jf.placeSet("f1000001", "b1", 0, 42)                                        // the phone goes on and leaves it on the first
        assertEquals("b1", JSONObject(prefs["list:f1000001"]!!).getString("song"))
        assertEquals(42L, JSONObject(prefs["list:f1000001"]!!).getLong("t"))
    }
}
