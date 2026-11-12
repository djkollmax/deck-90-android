package com.djkollmax.deck90

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

// A station's stream address from Radio Browser (radio-browser.info: free, no key), as Deck 90 on the PC finds it
// (electron/main.js radioBrowser, src/core/radio.js radioBest): the host that answered last is asked first, 7 s
// each; of the answers with the same name, the best quality first (FLAC, then Opus/AAC, then MP3, by bitrate)
data class Found(val name: String, val url: String, val codec: String, val bitrate: Int, val cc: String = "", val place: String = "") {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("name", name).put("url", url).put("codec", codec)
        .put("bitrate", bitrate).put("cc", cc).put("place", place)
}

object RadioBrowser {
    private val hosts = listOf("de1", "nl1", "at1", "de2").map { "https://$it.api.radio-browser.info" }
    @Volatile private var good = 0
    private val worth = mapOf("FLAC" to 20.0, "OPUS" to 1.7, "AAC+" to 1.6, "AAC" to 1.5, "OGG" to 1.3, "MP3" to 1.0)

    fun score(codec: String, kbps: Int) = (if (kbps > 0) kbps else 64) * (worth[codec.uppercase()] ?: 1.0)

    // a name without what only tells its quality ("Jazz 128k AAC" and "Jazz FLAC" are the same station)
    fun base(n: String): String = Normalizer.normalize(n.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\b(\\d+\\s*k(bps)?|aac\\+?|mp3|flac|ogg|opus|hq|hd|hi-?fi|lossless|stream|low|high|quality)\\b"), "")
        .replace(Regex("[^a-z0-9]+"), "")

    fun best(found: List<Found>): List<Found> {
        if (found.isEmpty()) return found
        val first = base(found[0].name)
        val (same, rest) = found.partition { base(it.name) == first }
        return same.sortedByDescending { score(it.codec, it.bitrate) } + rest
    }

    fun parse(text: String): List<Found> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim()
            if (name.isEmpty() || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url) || url.length >= 600) null
            else Found(name.take(80), url, o.optString("codec").take(12), o.optInt("bitrate", 0),
                o.optString("countrycode").takeIf { Regex("^[A-Z]{2}$").matches(it) } ?: "", o.optString("state").trim().take(40))
        }
    }

    // blocking: called from the player's loading thread (ResolvingDataSource) or a coroutine
    fun find(q: String, cc: String): List<Found> = best(ask("name=" + URLEncoder.encode(q.take(60), "UTF-8") +
        (if (Regex("^[A-Z]{2}$").matches(cc)) "&countrycode=$cc" else "") + "&hidebroken=true&order=clickcount&reverse=true&limit=10"))

    // any station in the world, by name and/or country, the most heard first, 60 at most (the PC's radio-search)
    fun search(q: String, cc: String): List<Found> {
        val name = q.trim().take(60); val country = cc.takeIf { Regex("^[A-Z]{2}$").matches(it) } ?: ""
        if (name.isEmpty() && country.isEmpty()) return emptyList()
        return ask((if (name.isNotEmpty()) "name=" + URLEncoder.encode(name, "UTF-8") + "&" else "") +
            (if (country.isNotEmpty()) "countrycode=$country&" else "") + "hidebroken=true&order=clickcount&reverse=true&limit=60")
    }

    private fun ask(query: String): List<Found> {
        var last: Exception? = null
        for (i in hosts.indices) {
            val k = (good + i) % hosts.size
            try {
                val c = URL(hosts[k] + "/json/stations/search?" + query).openConnection() as HttpURLConnection
                c.connectTimeout = 7000; c.readTimeout = 7000
                c.setRequestProperty("User-Agent", "Deck90-Android/" + BuildConfigInfo.version)
                val text = c.inputStream.bufferedReader().use { it.readText() }
                good = k
                return parse(text)
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("sin respuesta")
    }
}

// the app's version for the User-Agent, set by the service when it starts
object BuildConfigInfo { @Volatile var version = "0" }
