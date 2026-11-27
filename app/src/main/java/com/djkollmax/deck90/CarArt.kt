package com.djkollmax.deck90

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════
//  0.1.27: THE CAR'S PICTURES. Android Auto shows a picture beside every row and in big on the playing screen, and
//  reads them only from content:// addresses (not the server's http ones, and a picture sent whole is too big for a
//  list of twelve). So they're served from here (ArtProvider):
//  - a memory, a band, a letter of DISCOS, a list: a little DP940 display (its magenta film and its 16-segment
//    letters, from the face's dp940-display.js, the unlit segments faint as on the real one), drawn here;
//  - the tabs' and folders' icons (Tabler Icons, MIT: res/drawable-nodpi/car_*.png);
//  - the server's and the phone's album covers and the CD's, fetched and kept, a display when there's none.
// ═══════════════════════════════════════
object CarArt {
    const val AUTH = "com.djkollmax.deck90.art"

    // what a memory's display says: the band and the memory on top, the frequency (or the name) big, the unit (or
    // the country) under it. "FM2   P1", "104.3", "MHZ"
    data class Face(val top: String, val big: String, val bottom: String)
    fun stationFace(band: String, slot: Int, st: Station): Face {
        val label = when (band) { "HIFI" -> "HIFI"; Bands.LOCAL -> "LOC"; else -> band }
        return Face(line(label, "P$slot"), bigOf(st), unitOf(band, st))
    }
    fun tunedFace(st: Station): Face = Face(line("TUNE", st.cc.take(2)), bigOf(st), unitOf("", st))
    fun bandFace(band: String, count: Int): Face = Face("RADIO", Bands.label(band).replace("-", ""), "$count MEM")
    // 8 cells: one thing on the left, the other on the right
    fun line(left: String, right: String): String {
        val l = left.take(8); val r = right.take((8 - l.length - 1).coerceAtLeast(0))
        return l.padEnd(8 - r.length) + r
    }
    fun bigOf(st: Station): String {
        val f = st.freq.trim()
        if (f.isNotEmpty() && f.length <= 6 && f.all { it.isDigit() || it == '.' }) return f
        val word = st.name.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.isNotEmpty() } ?: "RADIO"
        return word.take(5)
    }
    fun unitOf(band: String, st: Station): String {
        val f = st.freq.trim()
        if (f.isEmpty() || !f.all { it.isDigit() || it == '.' }) return st.cc.ifEmpty { "WEB" }.uppercase().take(3)
        return if (band == "AM" || (!f.contains('.') && (f.toIntOrNull() ?: 0) >= 500)) "KHZ" else "MHZ"
    }

    // the addresses the car is given
    private fun base(path: String) = Uri.Builder().scheme("content").authority(AUTH).path(path)
    fun face(f: Face): Uri? = base("face.png").appendQueryParameter("t", f.top).appendQueryParameter("b", f.big).appendQueryParameter("u", f.bottom).build()
    fun station(band: String, slot: Int, st: Station): Uri? = face(stationFace(band, slot, st))
    fun icon(name: String): Uri? = base("icon/$name.png").build()
    fun cover(albumId: String): Uri? = base("cover/$albumId").build()
    fun list(id: String, name: String, count: Int): Uri? = base("list/$id").appendQueryParameter("n", name).appendQueryParameter("c", count.toString()).build()
    fun cd(disc: String): Uri? = base("cd/${disc.ifEmpty { "none" }}").build()

    // ── the 16-segment letters, the DP940's own (FONT_DATA in its script) ──
    private class Font(val height: Float, val advance: Float, val base: List<String>, val seg: Map<String, List<FloatArray>>, val glyphs: Map<String, Set<String>>)
    @Volatile private var font: Font? = null
    // the font's data in the display's script (null if the script changed shape: the pictures go without letters)
    fun fontData(js: String): JSONObject? = Regex("const FONT_DATA = (\\{.*?\\});\\n").find(js)?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
    private fun font(ctx: Context): Font? = font ?: runCatching {
        val j = fontData(ctx.assets.open("face/dp940-display.js").bufferedReader().use { it.readText() })!!
        val segs = j.getJSONObject("segments").let { s -> s.keys().asSequence().associateWith { k ->
            val a = s.getJSONArray(k); (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } } } }
        val gl = j.getJSONObject("glyphs").let { g -> g.keys().asSequence().associateWith { k -> g.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } }
        val base = j.getJSONArray("baseSegments").let { a -> (0 until a.length()).map { a.getString(it) } }
        Font(j.getDouble("height").toFloat(), j.getDouble("advance").toFloat(), base, segs, gl)
    }.getOrNull()?.also { font = it }

    private val ON = Color.rgb(201, 247, 230)
    private val OFF = Color.argb(70, 60, 80, 61)

    // a line of cells, as the display writes it: a point goes in the gap after its digit, not in a cell of its own
    private fun segments(c: Canvas, f: Font, text: String, x: Float, y: Float, h: Float, cells: Int, right: Boolean, lit: Paint, ghost: Paint?) {
        val s = h / f.height; val adv = f.advance * s
        val cl = ArrayList<Pair<String, Boolean>>()
        for (ch in text.uppercase()) {
            if (ch == '.' && cl.isNotEmpty() && !cl.last().second && cl.last().first != " ") cl[cl.size - 1] = cl.last().first to true
            else cl.add(ch.toString() to false)
        }
        while (cl.size > cells) cl.removeAt(cl.size - 1)
        if (right) repeat(cells - cl.size) { cl.add(0, " " to false) } else while (cl.size < cells) cl.add(" " to false)
        fun poly(pts: List<FloatArray>, ox: Float, dx: Float, p: Paint) {
            val path = Path(); pts.forEachIndexed { i, q -> val px = ox + (q[0] + dx) * s; val py = y + q[1] * s; if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            path.close(); c.drawPath(path, p)
        }
        cl.forEachIndexed { i, (ch, dot) ->
            val ox = x + i * adv
            val on = f.glyphs[ch] ?: f.glyphs["?"] ?: emptySet()
            for (name in f.base) { val pts = f.seg[name] ?: continue; if (name in on) poly(pts, ox, 0f, lit) else if (ghost != null) poly(pts, ox, 0f, ghost) }
            for (name in on) if (name !in f.base) f.seg[name]?.let { poly(it, ox, 0f, lit) }
            if (dot) f.seg["dot"]?.let { poly(it, ox, 57.5f, lit) }
        }
    }

    // the little display: a dark frame, the magenta film, three lines (the middle one big), a glass shine on top
    fun draw(ctx: Context, face: Face, size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val k = size / 256f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(18, 19, 22); c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 30 * k, 30 * k, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * k; p.color = Color.argb(60, 123, 125, 127)
        c.drawRoundRect(RectF(5 * k, 5 * k, size - 5 * k, size - 5 * k), 25 * k, 25 * k, p); p.style = Paint.Style.FILL
        val m = 16 * k; val lw = size - 2 * m; val lcd = RectF(m, m, m + lw, m + lw)
        p.color = Color.BLACK                                        // opaque: the paint's alpha would dim the film
        p.shader = LinearGradient(0f, m, 0f, m + lw, intArrayOf(Color.parseColor("#4D203C"), Color.parseColor("#681E43"), Color.parseColor("#5F193B"), Color.parseColor("#3C162B")),
            floatArrayOf(0f, .25f, .7f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(lcd, 14 * k, 14 * k, p); p.shader = null
        val f = font(ctx)
        if (f != null) {
            val pad = 18 * k; val x0 = m + pad; val inner = lw - 2 * pad
            val h1 = inner / (8 * f.advance) * f.height
            val n = if (face.big.replace(".", "").length <= 4) 4 else 5
            val h2 = inner / (n * f.advance) * f.height
            val yb = m + pad + h1 + (lw - 2 * pad - 2 * h1 - h2) / 2
            val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OFF }
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ON; alpha = 90; maskFilter = BlurMaskFilter(6 * k, BlurMaskFilter.Blur.NORMAL) }
            val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ON }
            for ((paint, g) in listOf(glow to null, lit to ghost)) {
                segments(c, f, face.top, x0, m + pad, h1, 8, false, paint, g)
                segments(c, f, face.big, x0, yb, h2, n, true, paint, g)
                segments(c, f, face.bottom, x0, m + lw - pad - h1, h1, 8, true, paint, g)
            }
        }
        p.shader = LinearGradient(0f, m, 0f, m + lw / 3, Color.argb(22, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.save(); c.clipRect(m, m, m + lw, m + lw / 3); c.drawRoundRect(lcd, 14 * k, 14 * k, p); c.restore()
        return bmp
    }
}

// the car's pictures, by address (CarArt): drawn or fetched once, then kept in the cache
class ArtProvider : ContentProvider() {
    override fun onCreate() = true
    private val ID = Regex("^[0-9a-zA-Z-]{1,64}$")

    override fun getType(uri: Uri): String = if (uri.pathSegments.firstOrNull() in setOf("cover", "cd")) "image/jpeg" else "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (mode != "r") throw SecurityException("read only")
        val dir = File(ctx.cacheDir, "car-art").apply { mkdirs() }
        val seg = uri.pathSegments
        val file = when (seg.firstOrNull()) {
            "face.png" -> {
                val face = CarArt.Face(uri.getQueryParameter("t").orEmpty().take(8), uri.getQueryParameter("b").orEmpty().take(6), uri.getQueryParameter("u").orEmpty().take(8))
                kept(dir, "face-" + hash(face.toString()) + ".png") { png(CarArt.draw(ctx, face)) }
            }
            "icon" -> {
                val name = seg.getOrNull(1)?.removeSuffix(".png")?.takeIf { Regex("^[a-z]{1,20}$").matches(it) } ?: return null
                val res = ctx.resources.getIdentifier("car_$name", "drawable", ctx.packageName).takeIf { it != 0 } ?: return null
                kept(dir, "icon-$name.png") { ctx.resources.openRawResource(res).use { it.readBytes() } }
            }
            "cover" -> {
                val id = seg.getOrNull(1)?.takeIf { ID.matches(it) } ?: return null
                Jf.load(ctx); Music.load(ctx)
                fetched(dir, "cover-$id") { fetch(ctx, Jf.cover(id)) } ?: kept(dir, "no-cover.png") { png(CarArt.draw(ctx, CarArt.Face("DISCO", "", ""))) }
            }
            "list" -> {
                val id = seg.getOrNull(1)?.takeIf { ID.matches(it) } ?: return null
                Jf.load(ctx)
                val name = uri.getQueryParameter("n").orEmpty(); val count = uri.getQueryParameter("c").orEmpty()
                fetched(dir, "list-$id") { fetch(ctx, Jf.image(id)) } ?: kept(dir, "list-face-" + hash(name + count)) {
                    png(CarArt.draw(ctx, CarArt.Face("LISTA", name.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.isNotEmpty() }?.take(5) ?: "", "$count TEM")))
                }
            }
            "cd" -> {
                val disc = seg.getOrNull(1)?.takeIf { ID.matches(it) } ?: return null
                val art = Cd.art
                if (art != null && disc == Cd.id.ifEmpty { "none" }) kept(dir, "cd-$disc", art)
                else kept(dir, "cd-face-" + Cd.status) { png(CarArt.draw(ctx, CarArt.Face("CD", if (Cd.status == "files") "MP3" else "AUDIO", "${Cd.count()} TR"))) }
            }
            else -> return null
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // a picture fetched once and kept; when it can't be fetched (offline, or there's none) null, and it's asked
    // again the next time
    private fun fetched(dir: File, name: String, get: () -> ByteArray?): File? =
        File(dir, name).takeIf { it.exists() && it.length() > 0 } ?: get()?.let { kept(dir, name, it) }
    private fun kept(dir: File, name: String, bytes: ByteArray): File = File(dir, name).also { f -> if (!f.exists() || f.length() != bytes.size.toLong()) write(f, bytes) }
    private fun kept(dir: File, name: String, make: () -> ByteArray): File {
        val f = File(dir, name)
        if (!f.exists() || f.length() == 0L) write(f, make())
        return f
    }
    private fun write(f: File, bytes: ByteArray) { val t = File(f.parentFile, f.name + ".tmp"); t.writeBytes(bytes); t.renameTo(f) }
    private fun hash(s: String) = Integer.toHexString(s.hashCode())
    private fun png(b: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    // a cover: the server's (http, only its own address) or the phone's (Android's album art)
    private fun fetch(ctx: Context, u: Uri?): ByteArray? = runCatching {
        when (u?.scheme) {
            "content" -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
            "http", "https" -> {
                val c = URL(u.toString()).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.takeIf { it.size in 1..3_000_000 } else null } finally { c.disconnect() }
            }
            else -> null
        }
    }.getOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
