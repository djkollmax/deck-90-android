package com.djkollmax.deck90

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
        val path = first[1].substringBefore('?'); val q = first[1].substringAfter('?', "")
        synchronized(seen) { seen += path to headers }
        if (drop.getAndDecrement() > 0) return                                 // the connection cut: no answer at all
        drop.compareAndSet(-1, 0)
        val auth = headers["authorization"].orEmpty()
        val (code, body) = when {
            down -> 500 to ""
            path == "/Users/AuthenticateByName" -> {
                if (String(sent).contains("\"Pw\":\"bien\"")) 200 to """{"AccessToken":"tok123","User":{"Id":"u1","Name":"Damián"}}""" else 401 to ""
            }
            !path.startsWith("/Users/") && !path.startsWith("/Artists/") -> 404 to ""        // not Jellyfin's
            !auth.contains("Token=\"tok123\"") || revoked -> 401 to ""
            path == "/Users/u1" -> 200 to """{"Id":"u1","Name":"Damián"}"""
            path == "/Users/u1/Views" -> 200 to """{"Items":[{"Id":"lib1","CollectionType":"music"},{"Id":"lib2","CollectionType":"movies"}]}"""
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
        http = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!http.isClosed) { val c = runCatching { http.accept() }.getOrNull() ?: break; Thread { runCatching { answer(c) } }.start() } }.apply { isDaemon = true }.start()
        base = "127.0.0.1:" + http.localPort
        Jf.connectMs = 2000; Jf.againMs = 50
    }
    @After fun stop() { http.close() }

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
            for ((path, h) in seen) {
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
}
