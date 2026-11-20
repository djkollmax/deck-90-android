package com.djkollmax.deck90

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinTest {
    @Test fun `a server's address as people type it`() {
        assertEquals("http://192.168.0.199:8096", Jf.clean("192.168.0.199:8096"))
        assertEquals("http://100.64.1.2:8096", Jf.clean(" http://100.64.1.2:8096/ "))
        assertEquals("https://musica.casa.com/jellyfin", Jf.clean("https://musica.casa.com/jellyfin"))
        assertEquals("", Jf.clean(""))
        assertEquals("", Jf.clean("no es una dirección"))
    }

    // what Jellyfin answers for an album's songs (Fields=MediaSources), cut to what Deck 90 reads
    private val answer = """[
      {"Id":"a1","Name":"Open Eye Signal","AlbumArtist":"Jon Hopkins","Album":"Immunity","AlbumId":"al1","IndexNumber":2,"ParentIndexNumber":1,
       "RunTimeTicks":4700000000,"MediaSources":[{"Container":"flac","Bitrate":2100000,"MediaStreams":[{"Type":"Audio","Codec":"flac","SampleRate":44100,"BitDepth":24,"BitRate":2100000}]}]},
      {"Id":"b2","Name":"One More Time","Artists":["Daft Punk"],"Album":"Discovery","AlbumId":"al2","IndexNumber":1,"RunTimeTicks":3200000000,
       "MediaSources":[{"Container":"mp3","Bitrate":320000,"MediaStreams":[{"Type":"Audio","Codec":"mp3","SampleRate":44100,"BitRate":320000}]}]},
      {"Id":"c3","Name":"","RunTimeTicks":0}
    ]"""

    @Test fun `songs, their length and the quality the display shows`() {
        val s = Jf.songsFrom(JSONArray(answer))
        assertEquals(listOf("Open Eye Signal", "One More Time", "UNTITLED"), s.map { it.name })
        assertEquals(listOf("Jon Hopkins", "Daft Punk", ""), s.map { it.artist })
        assertEquals(470, s[0].len)
        assertEquals(listOf("FLAC 24/44.1", "MP3 320", ""), s.map { Jf.quality(it) })
    }

    @Test fun `a compilation's song says who sings it, not Various Artists`() {
        val s = Jf.songsFrom(JSONArray("""[{"Id":"d4","Name":"Tema","AlbumArtist":"Various Artists","Artists":["Banda Uno","Invitada"],"AlbumId":"al3"}]"""))
        assertEquals("Banda Uno, Invitada", s[0].artist)
    }

    @Test fun `what plays is kept for the car and comes back the same`() {
        val s = Jf.songsFrom(JSONArray(answer))
        assertEquals(s, Jf.queueFrom(Jf.queueJson(s)))
        assertEquals("a1", Jf.parse("jf:a1")); assertEquals(null, Jf.parse("usb:1:2")); assertEquals(null, Jf.parse("jf:../x"))
    }

    @Test fun `names in order and the car's letters`() {
        assertEquals(listOf("Cut Copy", "Daft Punk", "The Kooks", "Ñandú", "The Weeknd"),
            listOf("The Weeknd", "Ñandú", "Daft Punk", "The Kooks", "Cut Copy").sortedBy { Jf.sortKey(it) })
        assertEquals("K", Jf.letter("The Kooks")); assertEquals("E", Jf.letter("Él")); assertEquals("#", Jf.letter("2001"))
    }
}
