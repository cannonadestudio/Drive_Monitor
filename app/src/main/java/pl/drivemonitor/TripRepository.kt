package pl.drivemonitor

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Trasy leżą w Android/data/pl.drivemonitor/files/trips/ – GPX + plik .json ze statystykami
 * (i uproszczonym kształtem trasy do miniatur). Folder jest widoczny po podłączeniu telefonu do komputera.
 */
object TripRepository {

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "trips").apply { mkdirs() }

    private fun statsFile(gpx: File) = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")

    fun saveStats(gpx: File, stats: TripStats) {
        statsFile(gpx).writeText(stats.toJson().toString())
    }

    fun isRecording(gpx: File) = TripRecorderService.currentFile?.absolutePath == gpx.absolutePath

    data class Trip(val gpx: File, val stats: JSONObject?, val recording: Boolean) {
        val title: String get() = formatTitle(gpx.nameWithoutExtension)
    }

    fun list(ctx: Context): List<Trip> =
        dir(ctx).listFiles { f -> f.extension == "gpx" }
            .orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { gpx ->
                val recording = isRecording(gpx)
                if (!recording) recoverInterrupted(gpx)?.let { return@mapNotNull it }
                if (!gpx.exists()) return@mapNotNull null
                val js = statsFile(gpx)
                Trip(gpx, if (js.exists()) runCatching { JSONObject(js.readText()) }.getOrNull() else null, recording)
            }

    /**
     * Trasa przerwana (np. system zamknął aplikację): domykamy GPX i liczymy statystyki z pliku.
     * Zwraca null, gdy nic nie trzeba było robić.
     */
    private fun recoverInterrupted(gpx: File): Trip? {
        val js = statsFile(gpx)
        if (js.exists()) {
            if (!GpxWriter.repair(gpx)) gpx.delete()
            return null
        }
        if (!GpxWriter.repair(gpx)) { gpx.delete(); return null }
        return try {
            val stats = TripStats()
            GpxParser.parse(gpx).forEach(stats::add)
            if (stats.pointCount < 2) { gpx.delete(); return null }
            saveStats(gpx, stats)
            Trip(gpx, stats.toJson(), false)
        } catch (e: Exception) {
            Log.w("TripRepository", "Nie udało się odzyskać ${gpx.name}", e)
            null
        }
    }

    fun delete(gpx: File) {
        if (isRecording(gpx)) return
        gpx.delete(); statsFile(gpx).delete()
    }

    /** Usuwa wszystkie zakończone trasy. Zwraca liczbę usuniętych. */
    fun deleteAll(ctx: Context): Int {
        var n = 0
        dir(ctx).listFiles { f -> f.extension == "gpx" }.orEmpty().forEach {
            if (!isRecording(it)) { delete(it); n++ }
        }
        return n
    }

    // ---------- miniatury ----------

    /**
     * Uproszczony kształt trasy (≤ 300 punktów) z kategorią prędkości.
     * Liczony raz z GPX i zapisywany w pliku .json, potem czytany natychmiast.
     * Wołać poza wątkiem głównym.
     */
    fun thumbnail(ctx: Context, gpx: File): List<ThumbPoint> {
        val js = statsFile(gpx)
        val obj = if (js.exists()) runCatching { JSONObject(js.readText()) }.getOrNull() else null
        obj?.optJSONArray(THUMB_KEY)?.let { arr ->
            return List(arr.length()) { i ->
                val p = arr.getJSONArray(i)
                ThumbPoint(p.getDouble(0), p.getDouble(1), p.getInt(2))
            }
        }
        // ślad bez „gwiazdek” z postojów i dopasowany do dróg
        val pts = DisplayTrack.prepare(ctx, GpxParser.parse(gpx))
        val step = maxOf(1, pts.size / 300)
        val thumb = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }.map {
            ThumbPoint(it.lon, it.lat, category(it.speedKmh, it.limitKmh))
        }
        if (obj != null && !isRecording(gpx) && thumb.size >= 2) {
            obj.remove("thumb")   // miniatura z v0.5 (bez dopasowania do dróg)
            obj.put(THUMB_KEY, JSONArray().apply {
                thumb.forEach { put(JSONArray().put(it.lon).put(it.lat).put(it.cat)) }
            })
            runCatching { js.writeText(obj.toString()) }
        }
        return thumb
    }

    private const val THUMB_KEY = "thumb2"

    fun category(speed: Double, limit: Int?): Int {
        if (limit == null) return 0
        val over = speed - limit
        return when {
            over <= 0 -> 1
            over <= 10 -> 2
            over <= 20 -> 3
            over <= 30 -> 4
            else -> 5
        }
    }

    private val nameFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val titleFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM · HH:mm", Locale("pl", "PL"))

    fun formatTitle(name: String): String = runCatching {
        LocalDateTime.parse(name, nameFormat).format(titleFormat).replaceFirstChar { it.uppercase() }
    }.getOrDefault(name.replace('_', ' '))

    // ---------- podsumowanie ----------

    data class Summary(
        val trips: Int, val km: Double, val seconds: Double,
        val overPct: Double, val over20Pct: Double, val brakesPer100: Double,
        /** km wg kategorii: brak limitu, w limicie, +0–10, +10–20, +20–30, >+30 */
        val kmByCat: DoubleArray,
    ) {
        val okKm get() = kmByCat[1]
        val overKm get() = kmByCat[2] + kmByCat[3] + kmByCat[4] + kmByCat[5]
    }

    /** Podsumowanie wszystkich tras ważone czasem jazdy. */
    fun summary(trips: List<Trip>): Summary {
        val withStats = trips.mapNotNull { it.stats }
        val km = withStats.sumOf { it.optDouble("distanceKm", 0.0) }
        val minutes = withStats.sumOf { it.optDouble("durationMin", 0.0) }
        fun weighted(key: String) =
            if (minutes > 0) withStats.sumOf { it.optDouble(key, 0.0) * it.optDouble("durationMin", 0.0) } / minutes else 0.0
        val cats = DoubleArray(6)
        withStats.forEach { js -> TripStats.kmByCategory(js).forEachIndexed { i, v -> cats[i] += v } }
        return Summary(
            trips = withStats.size,
            km = km,
            seconds = minutes * 60,
            kmByCat = cats,
            overPct = weighted("overPct_0"),
            over20Pct = weighted("overPct_20"),
            brakesPer100 = if (km > 0) withStats.sumOf { it.optInt("harshBrakes").toDouble() } * 100 / km else 0.0,
        )
    }
}
