package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `video is left out, HLS plays now (0_1_26) marked as such`() {
        val found = RadioBrowser.parse("""[
          {"name":"Radio buena","url":"http://a","codec":"MP3","bitrate":128,"hls":0},
          {"name":"Segmentos","url":"http://b/x.m3u8","codec":"AAC","bitrate":64,"hls":1},
          {"name":"BBC News HD (720P)","url":"http://c","codec":"UNKNOWN","bitrate":0},
          {"name":"Canal","url":"http://d","codec":"AAC,H.264","bitrate":0}]""")
        assertEquals(listOf("Radio buena", "Segmentos"), found.map { it.name })
        assertEquals(listOf(false, true), found.map { it.hls })
        assertTrue(RadioBrowser.isHls("https://live-ar1.cdnrad.com/hls/radio-1781/live.m3u8"))
        assertTrue(RadioBrowser.isHls("http://b/x.m3u8?session=1"))
        assertFalse(RadioBrowser.isHls("https://streams.fluxfm.de/live/mp3-320/audio/"))
    }

    @Test fun `a memory looked up by name plays only that station ("Radio 10" found a radio of Chaco)`() {
        assertFalse(RadioBrowser.fits("Radio 10", "La Radio 104.7 FM. Resistencia Chaco"))
        assertTrue(RadioBrowser.fits("AM750", "Radio AM 750"))
        assertTrue(RadioBrowser.fits("AM750", "AM750"))
        assertTrue(RadioBrowser.fits("Nacional Clasica", "Radio Nacional Clásica 96.7 FM"))
        assertTrue(RadioBrowser.fits("Rock & Pop", "Rock And Pop FM 95.9 (Rock & Pop)"))
        assertTrue(RadioBrowser.fits("La 100", "La 100 - 99.9 FM - Grupo Clarín"))
        assertTrue(RadioBrowser.fits("Blue FM 100.7", "Blue FM 100.7. Ciudad de Buenos Aires"))
        assertFalse(RadioBrowser.fits("Blue FM 100.7", "FM BLUE 92.1"))
    }

    @Test fun `the stations that stopped become their replacements, one kept by hand stays`() {
        assertEquals("Urbana", Bands.renewed(Station("Con Vos", "89.9", "Con Vos", "AR", "https://x/old"))?.name)
        assertEquals("AM 750", Bands.renewed(Station("Radio 10", "710", "Radio 10", "AR"))?.name)
        assertEquals("FluxFM", Bands.renewed(Station("Mother Earth", "", "Mother Earth Radio", "DE", "https://x"))?.name)
        assertEquals("Continental 590", Bands.renewed(Station("Continental", "590", "Radio Continental", "AR"))?.q)
        val delta = Bands.renewed(Station("Delta", "90.3", "Delta 90.3", "AR", "https://cdn.instream.audio/:9069/stream"))!!
        assertTrue(RadioBrowser.isHls(delta.url))
        assertEquals(delta, Bands.renewed(delta))                     // once
        val mine = Station("Con Vos", "", "Radio Con Vos 89.9", "AR", "https://y")
        assertEquals(mine, Bands.renewed(mine))
        assertEquals(listOf("FluxFM", "Jazzradio", "VeniceClassic", "ChillHop"), Bands.defaults.getValue("FM5").take(4).map { it.name })
    }
}
