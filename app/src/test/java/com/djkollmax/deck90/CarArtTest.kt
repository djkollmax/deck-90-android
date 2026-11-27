package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CarArtTest {
    @Test fun `a memory's display says the band and memory, the frequency big and the unit`() {
        assertEquals(CarArt.Face("FM2   P1", "104.3", "MHZ"), CarArt.stationFace("FM2", 1, Station("Urbana", "104.3", "Urbana Play 104.3", "AR")))
        assertEquals(CarArt.Face("AM    P2", "750", "KHZ"), CarArt.stationFace("AM", 2, Station("AM 750", "750", "AM750", "AR")))
        assertEquals(CarArt.Face("LOC  P12", "95.9", "MHZ"), CarArt.stationFace(Bands.LOCAL, 12, Station("La Local", "95.9", "La Local", "AR")))
    }

    @Test fun `a station with no frequency shows its name's first word and its country`() {
        assertEquals(CarArt.Face("FM3   P1", "SWISS", "CH"), CarArt.stationFace("FM3", 1, Station("SwissClassic", "", "Radio Swiss Classic", "CH")))
        assertEquals(CarArt.Face("HIFI  P5", "LINN", "GB"), CarArt.stationFace("HIFI", 5, Station("Linn Jazz", "", "Linn Jazz", "GB")))
        assertEquals("WEB", CarArt.unitOf("FM4", Station("X", "", "X", "")))
    }

    @Test fun `a band's display`() {
        assertEquals(CarArt.Face("RADIO", "HIFI", "12 MEM"), CarArt.bandFace("HIFI", 12))
        assertEquals(CarArt.Face("RADIO", "FM2", "6 MEM"), CarArt.bandFace("FM2", 6))
    }

    @Test fun `the top line fits eight cells`() {
        assertEquals("TUNE  AR", CarArt.line("TUNE", "AR"))
        assertEquals("ABCDEFGH", CarArt.line("ABCDEFGHIJ", "P1"))
        assertEquals(8, CarArt.line("LOC", "P12").length)
    }

    @Test fun `the display's letters are found in the face's script`() {
        val js = File("src/main/assets/face/dp940-display.js").readText()
        val f = CarArt.fontData(js)
        assertNotNull("FONT_DATA moved in dp940-display.js: the car's pictures lose their letters", f)
        for (ch in "0123456789.ABCDEFGHIJKLMNOPQRSTUVWXYZ-") assertTrue("no glyph for $ch", f!!.getJSONObject("glyphs").has(ch.toString()))
        assertTrue(f!!.getJSONObject("segments").has("dot"))
    }
}
