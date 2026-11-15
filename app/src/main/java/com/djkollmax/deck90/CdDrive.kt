package com.djkollmax.deck90

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ═══════════════════════════════════════
//  A CD/DVD DRIVE ON USB (OTG): Android doesn't read CDs, so the app speaks to the drive itself, as a computer does:
//  USB mass storage (bulk-only transport: a 31-byte command wrapper, the data, a 13-byte status) carrying the CD
//  drive's own commands (SCSI MMC): is a disc in (TEST UNIT READY, REQUEST SENSE), the table of contents (READ TOC),
//  the sound of an audio CD as it is on the disc (READ CD: 2352 bytes a sector, 1/75 s of 16-bit stereo at 44.1 kHz),
//  a data disc's sectors (READ 10: 2048 bytes), and EJECT (START STOP UNIT). One command at a time (synchronized).
// ═══════════════════════════════════════
class CdDrive private constructor(private val conn: UsbDeviceConnection, private val intf: UsbInterface,
    private val bulkIn: UsbEndpoint, private val bulkOut: UsbEndpoint) {

    companion object {
        const val RAW = 2352                                          // an audio sector: 588 stereo frames of 16 bits
        const val DATA = 2048                                         // a data sector
        private const val TIMEOUT = 10_000
        private const val CHUNK = 16384                               // a bulk transfer at most (older Androids)

        // a mass-storage interface that speaks bulk-only (all USB CD drives)
        fun storage(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol == 0x50 }

        fun find(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { storage(it) != null }

        fun open(usb: UsbManager, d: UsbDevice): CdDrive {
            val intf = storage(d) ?: throw IOException("no storage interface")
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val i = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("no bulk in")
            val o = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("no bulk out")
            val c = usb.openDevice(d) ?: throw IOException("no permission")
            if (!c.claimInterface(intf, true)) { c.close(); throw IOException("interface busy") }
            return CdDrive(c, intf, i, o)
        }

        // the table of contents (READ TOC, format 0, LBA): each track's number, start and whether it's audio; the
        // lead-out is track 0xAA. Pure, for the tests
        fun parseToc(b: ByteArray): List<TocEntry> {
            if (b.size < 4) return emptyList()
            val len = ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)) + 2
            val out = mutableListOf<TocEntry>()
            var p = 4
            while (p + 8 <= minOf(len, b.size)) {
                val control = b[p + 1].toInt() and 0x0F
                val n = b[p + 2].toInt() and 0xFF
                val lba = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                out += TocEntry(n, lba, control and 0x04 == 0)
                p += 8
            }
            return out
        }
    }

    data class TocEntry(val n: Int, val lba: Int, val audio: Boolean)
    class Check(val key: Int, val asc: Int, val ascq: Int) : IOException("check condition $key/$asc/$ascq")

    private var tag = 1

    @Synchronized fun close() { runCatching { conn.releaseInterface(intf) }; conn.close() }

    // one command: its block (cdb), the data in (length) or none. A failed command reads the sense and throws Check
    @Synchronized private fun command(cdb: ByteArray, length: Int, into: ByteArray? = null, offset: Int = 0, sense: Boolean = true): ByteArray {
        val t = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x43425355).putInt(t).putInt(length).put(if (length > 0) 0x80.toByte() else 0).put(0).put(cdb.size.toByte())
            .put(cdb.copyOf(16)).array()
        if (conn.bulkTransfer(bulkOut, cbw, 31, TIMEOUT) != 31) { reset(); throw IOException("command not sent") }
        val data = into ?: ByteArray(length)
        var got = 0
        while (got < length) {
            val want = minOf(CHUNK, length - got)
            val n = conn.bulkTransfer(bulkIn, data, offset + got, want, TIMEOUT)
            if (n < 0) { clearHalt(bulkIn); break }
            got += n
            // a short packet ends the data: the drive sent less than asked (READ TOC answers in its own length). Asking
            // on would read its status as data, wait for a status that never comes and reset the drive
            if (n < want) break
        }
        val csw = ByteArray(13)
        var r = conn.bulkTransfer(bulkIn, csw, 13, TIMEOUT)
        if (r < 0) { clearHalt(bulkIn); r = conn.bulkTransfer(bulkIn, csw, 13, TIMEOUT) }
        val st = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (r != 13 || st.getInt(0) != 0x53425355) { reset(); throw IOException("no status") }
        when (csw[12].toInt()) {
            0 -> return data
            1 -> { if (sense) { val s = requestSense(); throw Check(s[2].toInt() and 0x0F, s[12].toInt() and 0xFF, s[13].toInt() and 0xFF) }; throw IOException("failed") }
            else -> { reset(); throw IOException("phase error") }
        }
    }

    private fun requestSense(): ByteArray = runCatching { command(byteArrayOf(0x03, 0, 0, 0, 18, 0), 18, sense = false) }.getOrDefault(ByteArray(18))

    private fun clearHalt(ep: UsbEndpoint) { conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT) }
    private fun reset() {
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, TIMEOUT)    // bulk-only mass storage reset
        clearHalt(bulkIn); clearHalt(bulkOut)
    }

    // the peripheral type: 5 is a CD/DVD drive
    fun kind(): Int = command(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36)[0].toInt() and 0x1F

    @Volatile var changed = true                                     // a disc put in (or taken out) since it was read
    // a disc in and ready: true; no disc: false; spinning up: waits a little (a few seconds at most)
    fun ready(): Boolean {
        repeat(12) {
            try { command(ByteArray(6), 0); return true } catch (c: Check) {
                if (c.asc == 0x3A) { changed = true; return false }   // medium not present
                if (c.key == 6) { changed = true; return@repeat }     // unit attention (a disc was just put in): ask again
                if (c.asc == 0x04) { Thread.sleep(500); return@repeat } // becoming ready
                if (c.key == 2) { Thread.sleep(500); return@repeat }
                throw c
            }
        }
        return false
    }

    fun toc(): List<TocEntry> {
        val b = command(byteArrayOf(0x43, 0, 0, 0, 0, 0, 0, (804 shr 8).toByte(), (804 and 0xFF).toByte(), 0), 804)
        return parseToc(b)
    }

    // count audio sectors from lba, into out at offset (count × 2352 bytes)
    fun readAudio(lba: Int, count: Int, out: ByteArray, offset: Int = 0) {
        val cdb = byteArrayOf(0xBE.toByte(), 0x04, (lba ushr 24).toByte(), (lba ushr 16).toByte(), (lba ushr 8).toByte(), lba.toByte(),
            (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte(), 0x10, 0, 0)
        command(cdb, count * RAW, out, offset)
    }

    // count data sectors from lba (count × 2048 bytes)
    fun readData(lba: Int, count: Int, out: ByteArray, offset: Int = 0) {
        val cdb = byteArrayOf(0x28, 0, (lba ushr 24).toByte(), (lba ushr 16).toByte(), (lba ushr 8).toByte(), lba.toByte(), 0, (count ushr 8).toByte(), count.toByte(), 0)
        command(cdb, count * DATA, out, offset)
    }

    fun eject() { runCatching { command(byteArrayOf(0x1E, 0, 0, 0, 0, 0), 0) }; command(byteArrayOf(0x1B, 0, 0, 0, 0x02, 0), 0) }   // unlock, then open
}
