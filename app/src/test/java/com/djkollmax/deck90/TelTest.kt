package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Test

class TelTest {
    @Test fun `a phone song's id is told apart from the server's`() {
        assertEquals(true, Tel.mine("t1234")); assertEquals(false, Tel.mine("a1b2")); assertEquals(false, Tel.mine("t12a"))
        assertEquals("t1234", Jf.parse("jf:t1234")); assertEquals("a1b2", Jf.parse("jf:a1b2")); assertEquals(null, Jf.parse("jf:t../x"))
        assertEquals("TEL", Music.label("t5")); assertEquals("SRV", Music.label("ab12"))
    }

    @Test fun `the codec from what Android says of the file`() {
        assertEquals(listOf("flac", "mp3", "aac", "vorbis", "opus", "pcm_s16le", "flac"),
            listOf(Tel.codecOf("audio/flac", "a.flac"), Tel.codecOf("audio/mpeg", "a.mp3"), Tel.codecOf("audio/mp4", "a.m4a"), Tel.codecOf("audio/ogg", "a.ogg"),
                Tel.codecOf("audio/opus", "a.opus"), Tel.codecOf("audio/x-wav", "a.wav"), Tel.codecOf("", "Tema.FLAC")))
        assertEquals("FLAC 24/96", Jf.quality(JfSong("t1", "x", "", "", "t2", 1, 0, 200, "flac", 4000, 96000, 24)))
    }
}
