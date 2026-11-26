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
    // LOC's band: the twelve most heard stations near where the phone is (not one of BAND's, made by LOC, kept
    // until LOC finds the place has changed)
    const val LOCAL = "LOCAL"
    val all = order + LOCAL
    fun label(band: String) = if (band == "HIFI") "HI-FI" else band

    val defaults: Map<String, List<Station>> = mapOf(
        "FM1" to listOf(
            Station("Aspen", "102.3", "Aspen", "AR"), Station("80s80s", "", "80s80s", "DE"),
            Station("Metro", "95.1", "Metro 95", "AR"), Station("Rock & Pop", "95.9", "Rock & Pop", "AR"),
            Station("La 100", "99.9", "La 100", "AR"), Station("Vorterix", "92.1", "Vorterix", "AR"),
        ),
        // 0.1.26 (deck-90 7.10.39): Urbana Play instead of Con Vos (its stream answers only its own site's player now),
        // Delta's stream is HLS, Blue is "Blue FM 100.7" in Radio Browser
        "FM2" to listOf(
            Station("Urbana", "104.3", "Urbana Play 104.3", "AR", "https://live-ar1.cdnrad.com/hls/radio-285/live.m3u8"),
            Station("Clásica", "96.7", "Nacional Clasica", "AR"), Station("Blackie", "89.1", "Blackie", "AR"),
            Station("Delta", "90.3", "Delta 90.3", "AR", "https://live-ar1.cdnrad.com/hls/radio-1781/live.m3u8"),
            Station("Blue", "100.7", "Blue FM 100.7", "AR"), Station("Nac Rock", "93.7", "Nacional Rock", "AR"),
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
        // 0.1.26: Mother Earth closed; in its four places, all at 320 kbps, FluxFM and Jazzradio (Berlin), Venice Classic
        // Radio and FluxFM's ChillHop
        "FM5" to listOf(
            Station("FluxFM", "", "FluxFM", "DE", "https://streams.fluxfm.de/live/mp3-320/audio/"),
            Station("Jazzradio", "", "Jazzradio Berlin", "DE", "https://streams.fluxfm.de/jazzradio/mp3-320/audio/"),
            Station("VeniceClassic", "", "Venice Classic Radio", "IT", "https://uk2.streamingpulse.com/ssl/vcr1"),
            Station("ChillHop", "", "FluxFM ChillHop", "DE", "https://streams.fluxfm.de/Chillhop/mp3-320/audio/"),
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
            // 0.1.26: AM 750 instead of Radio 10 (its stream answers only its own site's player now); Continental is
            // "Continental 590" in Radio Browser
            Station("Mitre", "790", "Radio Mitre", "AR"), Station("AM 750", "750", "AM750", "AR"),
            Station("Continental", "590", "Continental 590", "AR"), Station("Rivadavia", "630", "Rivadavia", "AR"),
            Station("Radio Nacional", "870", "Radio Nacional", "AR"), Station("La Red", "910", "La Red", "AR"),
        ),
    )

    // 0.1.26: the stations that came with the app and changed or stopped (by the name and search they came with) →
    // what takes their place, wherever they're kept; one found and kept by hand (another search) stays
    private val retired: List<Triple<String, String, Station>> by lazy {
        val fm2 = defaults.getValue("FM2"); val am = defaults.getValue("AM"); val fm5 = defaults.getValue("FM5")
        listOf(Triple("Con Vos", "Con Vos", fm2[0]), Triple("Delta", "Delta 90.3", fm2[3]), Triple("Blue", "Blue 100.7", fm2[4]),
            Triple("Radio 10", "Radio 10", am[1]), Triple("Continental", "Radio Continental", am[2]),
            Triple("Mother Earth", "Mother Earth Radio", fm5[0]), Triple("ME Jazz", "Mother Earth Jazz", fm5[1]),
            Triple("ME Klassik", "Mother Earth Klassik", fm5[2]), Triple("ME Instrum.", "Mother Earth Instrumental", fm5[3]))
    }
    fun renewed(st: Station?): Station? = st?.let { s ->
        retired.firstOrNull { (name, q, now) -> s.name == name && s.q == q && !(s.url.isNotEmpty() && s.url == now.url) }?.third ?: s
    }

    // the band's twelve memories: what's kept, or the defaults
    fun fresh(band: String): MutableList<Station?> = MutableList(SLOTS) { i -> defaults[band]?.getOrNull(i) }

    fun listToJson(list: List<Station?>) = JSONArray().apply { list.forEach { put(it?.toJson() ?: JSONObject.NULL) } }
    fun listFromJson(a: JSONArray?, band: String): MutableList<Station?> {
        val out = fresh(band)
        if (a != null) for (i in 0 until minOf(a.length(), SLOTS)) out[i] = if (a.isNull(i)) null else renewed(Station.fromJson(a.optJSONObject(i)))
        return out
    }
}
