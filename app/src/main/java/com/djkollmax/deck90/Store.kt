package com.djkollmax.deck90

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

// What the app keeps (SharedPreferences, this phone only): each band's twelve memories, the band and memory last
// played, and the stream addresses Radio Browser gave (so a station starts at once the next time)
class Store(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("deck90", Context.MODE_PRIVATE)

    var band: String
        get() = p.getString("band", "FM1")!!.let { if (it in Bands.all) it else "FM1" }
        set(v) { p.edit().putString("band", v).apply(); if (v in Bands.order) lastBand = v }
    // the last of BAND's bands (LOCAL is left with BAND back to it, and A.MEM keeps there what plays in LOCAL)
    var lastBand: String
        get() = p.getString("lastBand", "FM1")!!.let { if (it in Bands.order) it else "FM1" }
        set(v) { p.edit().putString("lastBand", v).apply() }

    var lastSlot: Int
        get() = p.getInt("lastSlot", 1).coerceIn(1, Bands.SLOTS)
        set(v) { p.edit().putInt("lastSlot", v.coerceIn(1, Bands.SLOTS)).apply() }

    // LOCAL: its stations, and where they were found (lat, lon, town) and when
    fun localSet(list: List<Station>, lat: Double, lon: Double, town: String) {
        p.edit().putString("mem.${Bands.LOCAL}", Bands.listToJson(List(Bands.SLOTS) { list.getOrNull(it) }).toString())
            .putString("localAt", JSONObject().put("lat", lat).put("lon", lon).put("town", town).put("t", System.currentTimeMillis()).toString()).apply()
    }
    fun localAt(): JSONObject? = p.getString("localAt", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    var slot: Int
        get() = p.getInt("slot", 1).coerceIn(1, Bands.SLOTS)
        set(v) { p.edit().putInt("slot", v.coerceIn(1, Bands.SLOTS)).apply() }

    fun memories(band: String): MutableList<Station?> =
        Bands.listFromJson(p.getString("mem.$band", null)?.let { runCatching { JSONArray(it) }.getOrNull() }, band)

    fun keep(band: String, slot: Int, st: Station) {
        val list = memories(band); list[slot - 1] = st
        p.edit().putString("mem.$band", Bands.listToJson(list).toString()).apply()
    }

    fun station(band: String, slot: Int): Station? = memories(band).getOrNull(slot - 1)

    // a station found in the world search and tuned (not a memory until A.MEM or a number held keeps it), and
    // whether it is the one playing
    var tuned: Station?
        get() = Station.fromJson(p.getString("tuned", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        set(v) { p.edit().apply { if (v == null) remove("tuned") else putString("tuned", v.toJson().toString()) }.apply() }
    var onTuned: Boolean
        get() = p.getBoolean("onTuned", false) && tuned != null
        set(v) { p.edit().putBoolean("onTuned", v).apply() }
    fun current(): Station? = if (onTuned) tuned else station(band, slot)

    // what the stereo plays: one of MODES (MODE, and AUX), and on USB the disc, the song and where in it (for the
    // car's resumption); RAND and REP
    var mode: String
        get() = modeOf(p.getString("mode", "radio"))
        set(v) { p.edit().putString("mode", v).apply() }
    var usbDisc: Int
        get() = p.getInt("usbDisc", 1).coerceAtLeast(1)
        set(v) { p.edit().putInt("usbDisc", v.coerceAtLeast(1)).apply() }
    var usbTrack: Int
        get() = p.getInt("usbTrack", 1).coerceAtLeast(1)
        set(v) { p.edit().putInt("usbTrack", v.coerceAtLeast(1)).apply() }
    var usbPos: Long
        get() = p.getLong("usbPos", 0L).coerceAtLeast(0L)
        set(v) { p.edit().putLong("usbPos", v.coerceAtLeast(0L)).apply() }
    // SERVER: the songs playing (Jellyfin.kt queueJson), which of them and where in it
    var jfQueue: String
        get() = p.getString("jfQueue", "[]") ?: "[]"
        set(v) { p.edit().putString("jfQueue", v).apply() }
    var jfIndex: Int
        get() = p.getInt("jfIndex", 0).coerceAtLeast(0)
        set(v) { p.edit().putInt("jfIndex", v.coerceAtLeast(0)).apply() }
    var jfPos: Long
        get() = p.getLong("jfPos", 0L).coerceAtLeast(0L)
        set(v) { p.edit().putLong("jfPos", v.coerceAtLeast(0L)).apply() }
    // 0.1.27: the Jellyfin list playing (its id; "" for any other queue), so where it is goes back to Jellyfin
    // (RadioService), from the phone or the car
    var jfList: String
        get() = p.getString("jfList", "") ?: ""
        set(v) { p.edit().putString("jfList", v).apply() }
    var cdTrack: Int
        get() = p.getInt("cdTrack", 1).coerceAtLeast(1)
        set(v) { p.edit().putInt("cdTrack", v.coerceAtLeast(1)).apply() }
    var usbRand: Boolean
        get() = p.getBoolean("usbRand", false)
        set(v) { p.edit().putBoolean("usbRand", v).apply() }
    var usbRep: Int                                                  // 0 off, 1 the disc, 2 the song
        get() = p.getInt("usbRep", 0).coerceIn(0, 2)
        set(v) { p.edit().putInt("usbRep", v.coerceIn(0, 2)).apply() }

    // the weather's last place (rounded, about a kilometre), and whether the location was asked for already
    fun lastPlace(): Pair<Double, Double>? {
        val la = p.getString("wxLat", null)?.toDoubleOrNull() ?: return null
        val lo = p.getString("wxLon", null)?.toDoubleOrNull() ?: return null
        return la to lo
    }
    fun lastPlace(lat: Double, lon: Double) { p.edit().putString("wxLat", lat.toString()).putString("wxLon", lon.toString()).apply() }
    var askedAuxMeter: Boolean                                     // 0.1.22: AUX's meters asked for once
        get() = p.getBoolean("askedAuxMeter", false)
        set(v) { p.edit().putBoolean("askedAuxMeter", v).apply() }
    var askedLocation: Boolean
        get() = p.getBoolean("askedLocation", false)
        set(v) { p.edit().putBoolean("askedLocation", v).apply() }

    // the address that played last for a station (by its search), and forgetting it when it stops answering
    fun resolved(st: Station): String? = p.getString("url." + key(st), null)
    fun resolvedSet(st: Station, url: String?) {
        p.edit().apply { if (url == null) remove("url." + key(st)) else putString("url." + key(st), url) }.apply()
    }
    private fun key(st: Station) = st.q.lowercase() + "|" + st.cc

    companion object {
        // 0.1.21: "aux" too (0.1.20 kept it but read it back as "radio", so AUX never took over the keys)
        val MODES = setOf("radio", "usb", "cd", "server", "aux")
        fun modeOf(v: String?) = if (v in MODES) v!! else "radio"
        // the media id of a memory: "st:FM2:3"; its address for the player: deck90://station/FM2/3 (Resolver)
        fun mediaId(band: String, slot: Int) = "st:$band:$slot"
        fun parse(id: String?): Pair<String, Int>? {
            val m = Regex("^st:([A-Z0-9]+):(\\d{1,2})$").matchEntire(id ?: return null) ?: return null
            val band = m.groupValues[1]; val slot = m.groupValues[2].toInt()
            return if (band in Bands.all && slot in 1..Bands.SLOTS) band to slot else null
        }
        fun uri(band: String, slot: Int): Uri = Uri.parse("deck90://station/$band/$slot")
        const val TUNED = "tuned"                                    // the tuned station's media id; deck90://tuned
    }

    // 0.1.26: an HLS station goes to the player as a list of pieces (its address is resolved later, so the player is
    // told now, by the address it has or played last)
    private fun mime(st: Station): String? = if (RadioBrowser.isHls(st.url.ifEmpty { resolved(st) ?: "" })) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null

    // a memory as the player and Android Auto see it: its name, the band and frequency under it
    fun item(band: String, slot: Int): MediaItem? {
        val st = station(band, slot) ?: return null
        val extras = Bundle().apply { putString("band", band); putInt("slot", slot); putString("freq", st.freq) }
        val under = Bands.label(band) + " · " + slot + if (st.freq.isNotEmpty()) " · " + st.freq else ""
        return MediaItem.Builder()
            .setMediaId(mediaId(band, slot))
            .setUri(uri(band, slot)).setMimeType(mime(st))
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(st.name).setArtist(under).setStation(st.name)
                .setArtworkUri(CarArt.station(band, slot, st))          // 0.1.27: its little display (CarArt)
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setExtras(extras).build())
            .build()
    }

    fun tunedItem(): MediaItem? {
        val st = tuned ?: return null
        return MediaItem.Builder().setMediaId(TUNED).setUri(Uri.parse("deck90://tuned")).setMimeType(mime(st))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(st.name).setArtist("MUNDO" + if (st.cc.isNotEmpty()) " · " + st.cc else "")
                .setStation(st.name).setArtworkUri(CarArt.face(CarArt.tunedFace(st))).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build())
            .build()
    }
    fun itemFor(id: String?): MediaItem? = if (id == TUNED) tunedItem() else Jf.parse(id)?.let { Jf.song(it) }?.let { Jf.item(it) } ?: Cd.parse(id)?.let { Cd.item(it) } ?: Usb.parse(id)?.let { (d, t) -> Usb.item(d, t) } ?: parse(id)?.let { (b, s) -> item(b, s) }

    // the band's memories in order (the empty ones left out), and where the one asked for is among them
    fun playlist(band: String, slot: Int): Pair<List<MediaItem>, Int> {
        val items = (1..Bands.SLOTS).mapNotNull { item(band, it) }
        val at = items.indexOfFirst { it.mediaId == mediaId(band, slot) }.coerceAtLeast(0)
        return items to at
    }

    fun toJson(band: String): JSONObject = JSONObject().put("band", band).put("memories", Bands.listToJson(memories(band)))
}
