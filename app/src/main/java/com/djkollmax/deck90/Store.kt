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
        get() = p.getString("band", "FM1")!!.let { if (it in Bands.order) it else "FM1" }
        set(v) { p.edit().putString("band", v).apply() }
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

    // the address that played last for a station (by its search), and forgetting it when it stops answering
    fun resolved(st: Station): String? = p.getString("url." + key(st), null)
    fun resolvedSet(st: Station, url: String?) {
        p.edit().apply { if (url == null) remove("url." + key(st)) else putString("url." + key(st), url) }.apply()
    }
    private fun key(st: Station) = st.q.lowercase() + "|" + st.cc

    companion object {
        // the media id of a memory: "st:FM2:3"; its address for the player: deck90://station/FM2/3 (Resolver)
        fun mediaId(band: String, slot: Int) = "st:$band:$slot"
        fun parse(id: String?): Pair<String, Int>? {
            val m = Regex("^st:([A-Z0-9]+):(\\d{1,2})$").matchEntire(id ?: return null) ?: return null
            val band = m.groupValues[1]; val slot = m.groupValues[2].toInt()
            return if (band in Bands.order && slot in 1..Bands.SLOTS) band to slot else null
        }
        fun uri(band: String, slot: Int): Uri = Uri.parse("deck90://station/$band/$slot")
    }

    // a memory as the player and Android Auto see it: its name, the band and frequency under it
    fun item(band: String, slot: Int): MediaItem? {
        val st = station(band, slot) ?: return null
        val extras = Bundle().apply { putString("band", band); putInt("slot", slot); putString("freq", st.freq) }
        val under = Bands.label(band) + " · " + slot + if (st.freq.isNotEmpty()) " · " + st.freq else ""
        return MediaItem.Builder()
            .setMediaId(mediaId(band, slot))
            .setUri(uri(band, slot))
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(st.name).setArtist(under).setStation(st.name)
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setExtras(extras).build())
            .build()
    }

    // the band's memories in order (the empty ones left out), and where the one asked for is among them
    fun playlist(band: String, slot: Int): Pair<List<MediaItem>, Int> {
        val items = (1..Bands.SLOTS).mapNotNull { item(band, it) }
        val at = items.indexOfFirst { it.mediaId == mediaId(band, slot) }.coerceAtLeast(0)
        return items to at
    }

    fun toJson(band: String): JSONObject = JSONObject().put("band", band).put("memories", Bands.listToJson(memories(band)))
}
