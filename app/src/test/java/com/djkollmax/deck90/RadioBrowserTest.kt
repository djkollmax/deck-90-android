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
}
