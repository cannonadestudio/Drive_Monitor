package pl.drivemonitor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Trasy leżą w Android/data/pl.drivemonitor/files/trips/ – GPX + plik .json ze statystykami.
 * Folder jest widoczny po podłączeniu telefonu do komputera.
 */
object TripRepository {

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "trips").apply { mkdirs() }

    private fun statsFile(gpx: File) = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")

    fun saveStats(gpx: File, stats: TripStats) {
        statsFile(gpx).writeText(stats.toJson().toString(2))
    }

    fun isRecording(gpx: File) = TripRecorderService.currentFile?.absolutePath == gpx.absolutePath

    data class Trip(val gpx: File, val stats: JSONObject?, val recording: Boolean)

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
        gpx.delete(); statsFile(gpx).delete()
    }

    /** Podsumowanie wszystkich tras ważone czasem jazdy ze znanym limitem. */
    fun summary(trips: List<Trip>): Map<String, Double> {
        val withStats = trips.mapNotNull { it.stats }
        val km = withStats.sumOf { it.optDouble("distanceKm") }
        val minutes = withStats.sumOf { it.optDouble("durationMin") }
        fun weighted(key: String) =
            if (minutes > 0) withStats.sumOf { it.optDouble(key) * it.optDouble("durationMin") } / minutes else 0.0
        return linkedMapOf(
            "Trasy" to withStats.size.toDouble(),
            "Dystans [km]" to km,
            "Czas [h]" to minutes / 60,
            "Powyżej limitu [% czasu]" to weighted("overPct_0"),
            "> +10 km/h [% czasu]" to weighted("overPct_10"),
            "> +20 km/h [% czasu]" to weighted("overPct_20"),
            "> +30 km/h [% czasu]" to weighted("overPct_30"),
            "Ostre hamowania / 100 km" to
                if (km > 0) withStats.sumOf { it.optInt("harshBrakes").toDouble() } * 100 / km else 0.0,
        )
    }
}
