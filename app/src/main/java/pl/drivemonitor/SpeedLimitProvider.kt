package pl.drivemonitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.*

/**
 * Ograniczenia prędkości z offline'owej bazy zbudowanej z OpenStreetMap
 * (tools/build_speed_db.py → speedlimits.sqlite).
 *
 * Plik bazy wgraj do: Android/data/pl.drivemonitor/files/speedlimits.sqlite
 *
 * Dopasowanie do drogi jest uproszczone: najbliższy odcinek w promieniu ~30 m,
 * z preferencją odcinków zgodnych z kierunkiem jazdy. Dla dokładnych wyników
 * w przyszłości warto podmienić to na map matching (GraphHopper / Valhalla).
 */
class SpeedLimitProvider(ctx: Context) {

    private val db: SQLiteDatabase? = run {
        val f = File(ctx.getExternalFilesDir(null), DB_NAME)
        if (f.exists()) SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) else null
    }

    val isAvailable get() = db != null

    data class Limit(val kmh: Int, val source: String)   // source: "osm" | "default:<typ drogi>"

    /** @param bearing kierunek jazdy w stopniach (null gdy stoimy). */
    fun lookup(lat: Double, lon: Double, bearing: Float?): Limit? {
        val db = db ?: return null
        val dLat = SEARCH_M / 111_320.0
        val dLon = SEARCH_M / (111_320.0 * cos(Math.toRadians(lat)))

        db.rawQuery(
            """SELECT lat1, lon1, lat2, lon2, maxspeed, highway, oneway FROM segments
               WHERE maxlat >= ? AND minlat <= ? AND maxlon >= ? AND minlon <= ?""",
            arrayOf(
                (lat - dLat).toString(), (lat + dLat).toString(),
                (lon - dLon).toString(), (lon + dLon).toString()
            )
        ).use { c ->
            var best: Limit? = null
            var bestScore = Double.MAX_VALUE
            while (c.moveToNext()) {
                val lat1 = c.getDouble(0); val lon1 = c.getDouble(1)
                val lat2 = c.getDouble(2); val lon2 = c.getDouble(3)
                val dist = distanceToSegmentM(lat, lon, lat1, lon1, lat2, lon2)
                if (dist > SEARCH_M) continue

                // kara za niezgodny kierunek (np. droga poprzeczna na skrzyżowaniu)
                var score = dist
                if (bearing != null) {
                    val segBearing = bearingDeg(lat1, lon1, lat2, lon2)
                    var diff = abs(((bearing - segBearing + 540) % 360) - 180)
                    if (c.getInt(6) == 0) diff = min(diff, 180 - diff)   // dwukierunkowa
                    score += diff * 0.5
                }
                if (score < bestScore) {
                    bestScore = score
                    val highway = c.getString(5) ?: "unclassified"
                    best = if (!c.isNull(4)) Limit(c.getInt(4), "osm")
                    else defaultFor(highway)?.let { Limit(it, "default:$highway") }
                }
            }
            return best
        }
    }

    /**
     * Domyślne limity w Polsce, gdy OSM nie ma tagu maxspeed.
     * Teren zabudowany (residential / living_street) – 50 km/h, strefa zamieszkania – 20.
     */
    private fun defaultFor(highway: String): Int? = when (highway) {
        "motorway", "motorway_link" -> 140
        "trunk", "trunk_link" -> 120          // zakładamy drogę ekspresową
        "primary", "secondary", "tertiary",
        "primary_link", "secondary_link", "tertiary_link", "unclassified" -> 90
        "residential" -> 50
        "living_street" -> 20
        "service" -> 30
        else -> null
    }

    companion object {
        const val DB_NAME = "speedlimits.sqlite"
        private const val SEARCH_M = 30.0

        fun distanceToSegmentM(
            lat: Double, lon: Double, lat1: Double, lon1: Double, lat2: Double, lon2: Double
        ): Double {
            // rzutowanie równoodległościowe – wystarczające dla odległości rzędu metrów
            val k = cos(Math.toRadians(lat)) * 111_320.0
            val px = (lon - lon1) * k; val py = (lat - lat1) * 111_320.0
            val sx = (lon2 - lon1) * k; val sy = (lat2 - lat1) * 111_320.0
            val len2 = sx * sx + sy * sy
            val t = if (len2 == 0.0) 0.0 else ((px * sx + py * sy) / len2).coerceIn(0.0, 1.0)
            return hypot(px - t * sx, py - t * sy)
        }

        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}
