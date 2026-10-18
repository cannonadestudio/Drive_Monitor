package pl.drivemonitor

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Trasy leżą w Android/data/pl.drivemonitor/files/trips/ – GPX + plik .json ze statystykami.
 * Folder jest widoczny po podłączeniu telefonu do komputera.
 */
object TripRepository {

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "trips").apply { mkdirs() }

    fun saveStats(gpx: File, stats: TripStats) {
        File(gpx.parentFile, gpx.nameWithoutExtension + ".json").writeText(stats.toJson().toString(2))
    }

    data class Trip(val gpx: File, val stats: JSONObject?)

    fun list(ctx: Context): List<Trip> =
        dir(ctx).listFiles { f -> f.extension == "gpx" }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { gpx ->
                GpxWriter.repairIfOpen(gpx)
                val js = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")
                Trip(gpx, if (js.exists()) JSONObject(js.readText()) else null)
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
