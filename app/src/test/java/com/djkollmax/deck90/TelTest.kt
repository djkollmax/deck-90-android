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

    // 0.1.15: "call" was any folder starting so: Callejeros and Calle 13 were left out
    @Test fun `the phone's recordings are left out, bands that start like them aren't`() {
        for (p in listOf("Music/Callejeros/Rocanroles sin destino/", "Music/Calle 13/Entren Los Que Quieran/", "Music/Telegrafo/", "Music/Android Lust/"))
            assertEquals(p, false, Tel.notMusic(p))
        for (p in listOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/", "Recordings/", "Call/", "Music/Call recordings/",
                "Notifications/", "Ringtones/", "Telegram/Telegram Audio/", "Music/Voice Recorder/"))
            assertEquals(p, true, Tel.notMusic(p))
    }

    @Test fun `an album's short intro stays, a short sound alone doesn't`() {
        val s = { id: String, album: String, len: Int -> JfSong(id, id, "", album, album, 1, 0, len) }
        val kept = Tel.shortOut(listOf(s("t1", "t10", 12), s("t2", "t10", 240), s("t3", "t11", 8), s("t4", "t12", 0)))
        assertEquals(listOf("t1", "t2", "t4"), kept.map { it.id })
    }
}
