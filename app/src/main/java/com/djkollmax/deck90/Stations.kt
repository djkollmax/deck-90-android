package com.djkollmax.deck90

import org.json.JSONArray
import org.json.JSONObject

// A station as Deck 90 on the PC keeps it: its name, its dial frequency ('' for internet only), what to look for in
// Radio Browser (q, cc) and, for some, a known address
data class Station(val name: String, val freq: String, val q: String, val cc: String, val url: String = "") {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("freq", freq).put("q", q).put("cc", cc).put("url", url)

    companion object {
        fun fromJson(o: JSONObject?): Station? = o?.let {
            val name = it.optString("name")
            if (name.isBlank()) null else Station(name, it.optString("freq"), it.optString("q", name), it.optString("cc"), it.optString("url"))
        }
    }
}

// The bands and their first memories: the same as Deck 90 on the PC (src/core/radio.js)
object Bands {
    const val SLOTS = 12
    val order = listOf("FM1", "FM2", "FM3", "FM4", "FM5", "HIFI", "AM")
    fun label(band: String) = if (band == "HIFI") "HI-FI" else band

    val defaults: Map<String, List<Station>> = mapOf(
        "FM1" to listOf(
            Station("Aspen", "102.3", "Aspen", "AR"), Station("80s80s", "", "80s80s", "DE"),
            Station("Metro", "95.1", "Metro 95", "AR"), Station("Rock & Pop", "95.9", "Rock & Pop", "AR"),
            Station("La 100", "99.9", "La 100", "AR"), Station("Vorterix", "92.1", "Vorterix", "AR"),
        ),
        "FM2" to listOf(
            Station("Con Vos", "89.9", "Con Vos", "AR"), Station("Clásica", "96.7", "Nacional Clasica", "AR"),
            Station("Blackie", "89.1", "Blackie", "AR"), Station("Delta", "90.3", "Delta 90.3", "AR"),
            Station("Blue", "100.7", "Blue 100.7", "AR"), Station("Nac Rock", "93.7", "Nacional Rock", "AR"),
        ),
        "FM3" to listOf(
            Station("SwissClassic", "", "Radio Swiss Classic", "CH"), Station("Swiss Jazz", "", "Radio Swiss Jazz", "CH"),
            Station("Ibiza Global", "", "Ibiza Global Radio", "ES"), Station("Heart 80s", "", "Heart 80s", "GB"),
            Station("Groove Salad", "", "Groove Salad", "US"), Station("Paradise", "", "Radio Paradise", "US"),
        ),
        "FM4" to listOf(
            Station("FIP", "", "FIP", "FR", "https://icecast.radiofrance.fr/fip-hifi.aac"),
            Station("FIP Jazz", "", "FIP Jazz", "FR", "https://icecast.radiofrance.fr/fipjazz-hifi.aac"),
            Station("KEXP", "", "KEXP", "US", "https://kexp.streamguys1.com/kexp160.aac"),
            Station("Linn Jazz", "", "Linn Jazz", "GB", "http://radio.linn.co.uk:8000/autodj"),
            Station("Linn Classic", "", "Linn Classical", "GB", "http://radio.linn.co.uk:8004/autodj"),
            Station("Linn Radio", "", "Linn Radio", "GB", "http://radio.linn.co.uk:8003/autodj"),
        ),
        "FM5" to listOf(
            Station("Mother Earth", "", "Mother Earth Radio", "DE", "https://motherearth.streamserver24.com/listen/motherearth/motherearth"),
            Station("ME Jazz", "", "Mother Earth Jazz", "DE", "https://motherearth.streamserver24.com/listen/motherearth_jazz/motherearth.jazz"),
            Station("ME Klassik", "", "Mother Earth Klassik", "DE", "http://server9.streamserver24.com:18910/motherearth.klassik"),
            Station("ME Instrum.", "", "Mother Earth Instrumental", "DE", "https://motherearth.streamserver24.com:18920/motherearth.instrumental"),
            Station("Naim Radio", "", "Naim Radio", "GB"), Station("Jazz24", "", "Jazz24", "US"),
        ),
        "HIFI" to listOf(
            Station("Paradise", "", "Radio Paradise Main Mix FLAC", "US", "http://stream.radioparadise.com/flac"),
            Station("RP Mellow", "", "Radio Paradise Mellow Mix FLAC", "US", "http://stream.radioparadise.com/mellow-flac"),
            Station("RP Rock", "", "Radio Paradise Rock Mix FLAC", "US", "http://stream.radioparadise.com/rock-flac"),
            Station("RP Global", "", "Radio Paradise Global Mix FLAC", "US", "http://stream.radioparadise.com/global-flac"),
            Station("Intense", "", "Intense Radio", "NL", "http://secure.live-streams.nl/flac.flac"),
            Station("Hi On Line", "", "Hi On Line Radio", "NL", "http://mscp2.live-streams.nl:8100/flac.flac"),
        ),
        "AM" to listOf(
            Station("Mitre", "790", "Radio Mitre", "AR"), Station("Radio 10", "710", "Radio 10", "AR"),
            Station("Continental", "590", "Radio Continental", "AR"), Station("Rivadavia", "630", "Rivadavia", "AR"),
            Station("Radio Nacional", "870", "Radio Nacional", "AR"), Station("La Red", "910", "La Red", "AR"),
        ),
    )

    // the band's twelve memories: what's kept, or the defaults
    fun fresh(band: String): MutableList<Station?> = MutableList(SLOTS) { i -> defaults[band]?.getOrNull(i) }

    fun listToJson(list: List<Station?>) = JSONArray().apply { list.forEach { put(it?.toJson() ?: JSONObject.NULL) } }
    fun listFromJson(a: JSONArray?, band: String): MutableList<Station?> {
        val out = fresh(band)
        if (a != null) for (i in 0 until minOf(a.length(), SLOTS)) out[i] = if (a.isNull(i)) null else Station.fromJson(a.optJSONObject(i))
        return out
    }
}
