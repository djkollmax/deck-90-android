package com.djkollmax.deck90

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CdTest {
    // READ TOC as a drive answers it: 3 audio tracks, then the lead-out
    private fun toc(vararg t: Pair<Int, Int>, data: Int = -1): ByteArray {
        val b = ByteBuffer.allocate(4 + 8 * t.size).order(ByteOrder.BIG_ENDIAN)
        b.putShort((2 + 8 * t.size).toShort()).put(1).put((t.size - 1).toByte())
        for ((n, lba) in t) { b.put(0).put(if (n == data) 0x14 else 0x10).put(n.toByte()).put(0).putInt(lba) }
        return b.array()
    }

    @Test fun `the table of contents - tracks, starts, audio or data`() {
        val e = CdDrive.parseToc(toc(1 to 0, 2 to 15000, 3 to 30000, 0xAA to 45000, data = 3))
        assertEquals(listOf(1, 2, 3, 0xAA), e.map { it.n })
        assertEquals(listOf(0, 15000, 30000, 45000), e.map { it.lba })
        assertEquals(listOf(true, true, false, true), e.map { it.audio })
    }

    // the id deck-90's electron/cdnames.js discId() gives for this table of contents
    @Test fun `MusicBrainz's disc id as the PC computes it`() {
        val tracks = listOf(CdTrack(1, 0, 15000), CdTrack(2, 15000, 15000), CdTrack(3, 30000, 15000))
        val all = tracks.map { CdDrive.TocEntry(it.n, it.lba, true) }
        assertEquals(PC_ID, Cd.discId(tracks, all, 45000))
        assertEquals("1+3+45150+150+15150+30150", Cd.tocParam(tracks, 45000))
    }

    @Test fun `a data disc's music files, Joliet names, in order`() {
        val disc = HashMap<Int, ByteArray>()
        fun sec(n: Int) = disc.getOrPut(n) { ByteArray(2048) }
        fun rec(b: ByteArray, at: Int, lba: Int, size: Int, dir: Boolean, name: ByteArray): Int {
            val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
            b[at] = len.toByte()
            ByteBuffer.wrap(b, at + 2, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(lba)
            ByteBuffer.wrap(b, at + 10, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(size)
            b[at + 25] = if (dir) 2 else 0; b[at + 32] = name.size.toByte(); System.arraycopy(name, 0, b, at + 33, name.size)
            return at + len
        }
        val j = { s: String -> s.toByteArray(Charsets.UTF_16BE) }
        // the primary volume (short names) and Joliet's (long ones), both pointing at the root in sector 20
        for ((n, type) in listOf(16 to 1, 17 to 2)) {
            val v = sec(n); v[0] = type.toByte(); "CD001".toByteArray().copyInto(v, 1)
            if (type == 2) { v[88] = 0x25; v[89] = 0x2F; v[90] = 0x45 }
            rec(v, 156, 20, 2048, true, byteArrayOf(0))
        }
        sec(18)[0] = 0xFF.toByte(); "CD001".toByteArray().copyInto(sec(18), 1)
        var p = rec(sec(20), 0, 20, 2048, true, byteArrayOf(0)); p = rec(sec(20), p, 20, 2048, true, byteArrayOf(1))
        p = rec(sec(20), p, 30, 123456, false, j("10 Diez.mp3;1"))
        p = rec(sec(20), p, 40, 2048, true, j("Álbum"))
        p = rec(sec(20), p, 31, 654321, false, j("2 Dos.flac;1"))
        rec(sec(20), p, 32, 100, false, j("tapa.jpg;1"))
        var q = rec(sec(40), 0, 40, 2048, true, byteArrayOf(0)); q = rec(sec(40), q, 20, 2048, true, byteArrayOf(1))
        rec(sec(40), q, 50, 999, false, j("01 Uno.ogg;1"))
        val files = Iso({ n -> sec(n) }, 0).music(Regex("\\.(mp3|flac|ogg)$", RegexOption.IGNORE_CASE))
        assertEquals(listOf("2 Dos.flac", "10 Diez.mp3", "Álbum/01 Uno.ogg"), files.map { it.path })
        assertEquals(listOf(31, 30, 50), files.map { it.lba })
        assertEquals(654321L, files[0].size)
    }

    companion object { const val PC_ID = "MXsnrcgOkbb2H34IbwspNVMJMwA-" }
}
