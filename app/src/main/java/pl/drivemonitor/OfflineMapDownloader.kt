package pl.drivemonitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Pobieranie mapy na później (bez internetu) dla wybranego prostokąta:
 * styl, ikony, czcionki z polskimi znakami, kafelki mapy do zbliżenia 14 i teren 3D do zbliżenia 12.
 * Wszystko trafia do [MapTileCache] – tej samej pamięci, z której korzysta ekran mapy.
 */
object OfflineMapDownloader {

    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val DEM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    private const val MAX_TILES = 12_000

    data class Progress(val done: Int, val total: Int, val failed: Int, val running: Boolean, val message: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    /** bbox: [south, west, north, east] */
    fun start(ctx: Context, bbox: DoubleArray, label: String) {
        if (_progress.value?.running == true) return
        cancelled.set(false)
        _progress.value = Progress(0, 0, 0, true, "Przygotowanie: $label")
        Thread {
            try {
                run(ctx.applicationContext, bbox, label)
            } catch (e: Exception) {
                Log.w("OfflineMap", "Błąd pobierania", e)
                _progress.value = Progress(0, 0, 0, false, "Nie udało się: ${e.message}")
            }
        }.start()
    }

    private fun run(ctx: Context, bbox: DoubleArray, label: String) {
        val cache = MapTileCache.get(ctx)
        val style = JSONObject(String(cache.fetch(STYLE_URL, preferNetwork = true) ?: error("brak połączenia z serwerem mapy")))
        val urls = LinkedHashSet<String>()

        // ikony (sprite) w obu rozdzielczościach
        style.optString("sprite").takeIf { it.isNotEmpty() }?.let { s ->
            listOf("", "@2x").forEach { r -> urls += "$s$r.json"; urls += "$s$r.png" }
        }
        // czcionki: podstawowa łacina, polskie znaki (256–511) i interpunkcja
        style.optString("glyphs").takeIf { it.isNotEmpty() }?.let { g ->
            fontStacks(style).forEach { stack ->
                val enc = URLEncoder.encode(stack, "UTF-8").replace("+", "%20")
                listOf("0-255", "256-511", "8192-8447").forEach { range ->
                    urls += g.replace("{fontstack}", enc).replace("{range}", range)
                }
            }
        }

        // kafelki źródeł wektorowych i rastrowych ze stylu
        val sources = style.optJSONObject("sources") ?: JSONObject()
        val tileJobs = ArrayList<Pair<String, IntRange>>()
        sources.keys().forEach { key ->
            val src = sources.getJSONObject(key)
            val type = src.optString("type")
            if (type != "vector" && type != "raster") return@forEach
            var tiles: JSONArray? = src.optJSONArray("tiles")
            var maxZ = src.optInt("maxzoom", 14)
            src.optString("url").takeIf { it.isNotEmpty() }?.let { tjUrl ->
                val tj = JSONObject(String(cache.fetch(tjUrl, preferNetwork = true) ?: return@let))
                tiles = tj.optJSONArray("tiles"); maxZ = tj.optInt("maxzoom", maxZ)
            }
            val template = tiles?.optString(0)?.takeIf { it.isNotEmpty() } ?: return@forEach
            val top = if (type == "vector") minOf(maxZ, 14) else minOf(maxZ, 6)   // raster w stylu to tylko tło świata
            tileJobs += template to (0..top)
        }
        tileJobs += DEM_URL to (0..12)

        tileJobs.forEach { (template, zooms) ->
            for (z in zooms) urls += tileUrls(template, z, bbox)
        }
        if (urls.size > MAX_TILES) error("obszar za duży (${urls.size} plików) – wybierz mniejszy")

        val todo = urls.filter { !cache.has(it) }
        val total = todo.size
        val done = AtomicInteger(0); val failed = AtomicInteger(0)
        _progress.value = Progress(0, total, 0, true, "Pobieranie: $label")
        val pool = Executors.newFixedThreadPool(4)
        todo.forEach { u ->
            pool.execute {
                if (cancelled.get()) return@execute
                if (cache.fetch(u, preferNetwork = false) == null) failed.incrementAndGet()
                val d = done.incrementAndGet()
                if (d % 10 == 0 || d == total) _progress.value = Progress(d, total, failed.get(), true, "Pobieranie: $label")
            }
        }
        pool.shutdown()
        pool.awaitTermination(2, java.util.concurrent.TimeUnit.HOURS)

        val mb = cache.sizeBytes() / 1_048_576.0
        _progress.value = Progress(
            done.get(), total, failed.get(), false,
            when {
                cancelled.get() -> "Przerwano. Pobrane fragmenty zostają w telefonie."
                failed.get() > 0 -> "Gotowe: $label (nie pobrano ${failed.get()} plików – spróbuj ponownie). Mapa zajmuje %.0f MB.".format(mb)
                else -> "Gotowe: $label. Mapa zajmuje %.0f MB.".format(mb)
            }
        )
    }

    /** Zestawy czcionek użyte w warstwach stylu (proste tablice text-font). */
    private fun fontStacks(style: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val layers = style.optJSONArray("layers") ?: return setOf("Noto Sans Regular")
        for (i in 0 until layers.length()) {
            val font = layers.getJSONObject(i).optJSONObject("layout")?.opt("text-font")
            if (font is JSONArray && font.length() > 0 && font.opt(0) is String) {
                out += List(font.length()) { font.getString(it) }.joinToString(",")
            }
        }
        if (out.isEmpty()) out += "Noto Sans Regular"
        return out
    }

    private fun tileUrls(template: String, z: Int, bbox: DoubleArray): List<String> {
        val (s, w, n, e) = bbox.toList()
        val n2 = 1 shl z
        fun x(lon: Double) = floor((lon + 180) / 360 * n2).toInt().coerceIn(0, n2 - 1)
        fun y(lat: Double): Int {
            val r = Math.toRadians(lat)
            return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n2).toInt().coerceIn(0, n2 - 1)
        }
        val out = ArrayList<String>()
        for (tx in x(w)..x(e)) for (ty in y(n)..y(s)) {
            out += template.replace("{z}", z.toString()).replace("{x}", tx.toString()).replace("{y}", ty.toString())
        }
        return out
    }

    /** Szacunkowa liczba plików dla prostokąta – do pokazania przed pobraniem. */
    fun estimateTiles(bbox: DoubleArray): Int =
        (0..14).sumOf { tileUrls("{z}/{x}/{y}", it, bbox).size } + (0..12).sumOf { tileUrls("{z}/{x}/{y}", it, bbox).size }

    /** Prostokąt wokół punktu o promieniu w km. */
    fun bboxAround(lat: Double, lon: Double, km: Double): DoubleArray {
        val dLat = km / 111.32
        val dLon = km / (111.32 * cos(Math.toRadians(lat)))
        return doubleArrayOf(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }
}
