package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioBrowserTest {
    @Test fun `the same station in its best quality first, the others after`() {
        val found = RadioBrowser.parse("""[
          {"name":"Jazz Uno 128k","url":"http://a/mp3","codec":"MP3","bitrate":128},
          {"name":"Otra radio","url":"http://b","codec":"FLAC","bitrate":0},
          {"name":"Jazz Uno FLAC","url_resolved":"http://a/flac","url":"http://x","codec":"FLAC","bitrate":0},
          {"name":"Jazz Uno","url":"http://a/aac","codec":"AAC","bitrate":192},
          {"name":"","url":"http://nope"},{"name":"Sin dirección","url":"ftp://x"}]""")
        assertEquals(listOf("http://a/mp3", "http://b", "http://a/flac", "http://a/aac"), found.map { it.url })
        assertEquals(listOf("http://a/flac", "http://a/aac", "http://a/mp3", "http://b"), RadioBrowser.best(found).map { it.url })
    }

    @Test fun `the world search keeps the country and the place, and only real country codes`() {
        val found = RadioBrowser.parse("""[
          {"name":"Jazz Uno","url":"http://a","codec":"MP3","bitrate":128,"countrycode":"AR","state":"Buenos Aires"},
          {"name":"Otra","url":"http://b","codec":"AAC","bitrate":64,"countrycode":"xx1","state":"  "}]""")
        assertEquals(listOf("AR" to "Buenos Aires", "" to ""), found.map { it.cc to it.place })
        assertEquals("AR", org.json.JSONObject(found[0].toJson().toString()).getString("cc"))
    }

    @Test fun `a name without what only tells its quality`() {
        assertEquals("radioparadise", RadioBrowser.base("Radio Paradise (FLAC lossless)"))
        assertEquals("clasica", RadioBrowser.base("Clásica 128 kbps MP3"))
    }

    @Test fun `memory ids`() {
        assertEquals("FM2" to 3, Store.parse("st:FM2:3"))
        assertEquals("HIFI" to 12, Store.parse(Store.mediaId("HIFI", 12)))
        assertNull(Store.parse("st:FM9:1")); assertNull(Store.parse("st:FM1:13")); assertNull(Store.parse("band:FM1"))
    }

    @Test fun `every band has six memories to start, twelve places`() {
        for (b in Bands.order) {
            assertEquals(6, Bands.defaults[b]!!.size)
            assertEquals(12, Bands.fresh(b).size)
        }
    }

    @Test fun `the place's radios are the lists merged, each station once with its best stream, the most heard first`() {
        val geo = listOf(Found("LU6 Atlantica", "http://a", "AAC+", 32, "AR", "", 2), Found("Fresh FM 102.9", "http://b", "MP3", 128, "AR", "", 0))
        val byState = listOf(Found("Rock and Pop 89.7", "http://c", "MP3", 128, "AR", "", 9), Found("LU6 Atlantica", "http://a2", "MP3", 128, "AR", "", 2))
        val byName = listOf(Found("Aspen Mar del Plata 89", "http://d", "MP3", 64, "AR", "", 30))
        val all = RadioBrowser.merge(listOf(geo, byState, byName))
        assertEquals(listOf("Aspen Mar del Plata 89", "Rock and Pop 89.7", "LU6 Atlantica", "Fresh FM 102.9"), all.map { it.name })
        assertEquals("the better of LU6's two streams", "http://a2", all.first { it.name == "LU6 Atlantica" }.url)
    }

    @Test fun `streams the player can't play are left out, HLS and video`() {
        val found = RadioBrowser.parse("""[
          {"name":"Radio buena","url":"http://a","codec":"MP3","bitrate":128,"hls":0},
          {"name":"Segmentos","url":"http://b/x.m3u8","codec":"AAC","bitrate":64,"hls":1},
          {"name":"BBC News HD (720P)","url":"http://c","codec":"UNKNOWN","bitrate":0},
          {"name":"Canal","url":"http://d","codec":"AAC,H.264","bitrate":0}]""")
        assertEquals(listOf("Radio buena"), found.map { it.name })
    }
}
