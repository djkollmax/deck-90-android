package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbTest {
    @Test fun `a song's name drops its number and extension`() {
        assertEquals("Here Comes the Sun", Usb.songName("07 - Here Comes the Sun.flac"))
        assertEquals("Because", Usb.songName("08. Because.mp3"))
        assertEquals("Something", Usb.songName("2 Something.m4a"))
        assertEquals("Side B", Usb.songName("B1 - Side B.ogg"))
        assertEquals("1999", Usb.songName("1999.mp3"))                // a title that is a number stays
        assertEquals("Tema sin número", Usb.songName("Tema sin número.wav"))
    }

    @Test fun `names in the order people read them`() {
        val files = listOf("10 Diez.mp3", "2 Dos.mp3", "1 Uno.mp3", "Bonus.mp3", "02b Dos y algo.mp3")
        assertEquals(listOf("1 Uno.mp3", "2 Dos.mp3", "02b Dos y algo.mp3", "10 Diez.mp3", "Bonus.mp3"), files.sortedWith(Usb.natural))
    }

    @Test fun `a folder called CD1 takes the one above it`() {
        assertEquals("The Wall · CD1", Usb.discName("CD1", "The Wall"))
        assertEquals("Disco 2", Usb.discName("Disco 2", ""))
        assertEquals("Abbey Road", Usb.discName("Abbey Road", "Beatles"))
    }

    @Test fun `only what the phone plays is a song - no playlists, MIDI or WMA`() {
        assertEquals(true, Usb.isSong("01 Tema.flac", "audio/flac"))
        assertEquals(true, Usb.isSong("Tema.MP3", ""))
        assertEquals(true, Usb.isSong("tema sin extensión", "audio/mpeg"))
        for (n in listOf("lista.m3u", "lista.m3u8", "radio.pls", "disco.cue", "tema.wma", "tono.mid", "nota.amr", ".oculto.mp3"))
            assertEquals(n, false, Usb.isSong(n, "audio/x-mpegurl"))
        assertEquals(false, Usb.isSong("algo", "audio/x-scpls"))
    }

    @Test fun `the phone's own folders that aren't music are left out`() {
        for (n in listOf("Android", "Notifications", "Ringtones", "Alarms", "Recordings", "WhatsApp", "DCIM", "LOST.DIR", ".thumbnails"))
            assertEquals(n, false, Usb.isMusicFolder(n))
        for (n in listOf("Music", "Música", "Soda Stereo", "Android Lust"))
            assertEquals(n, true, Usb.isMusicFolder(n))
    }
}
