package pl.drivemonitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.*

/**
 * Ograniczenia prędkości z OpenStreetMap. Dwa źródła:
 *  1. pobierane automatycznie w trakcie jazdy (OnlineLimitCache) – domyślnie,
 *  2. opcjonalna baza offline speedlimits.sqlite (tools/build_speed_db.py),
 *     importowana przyciskiem w aplikacji – przydaje się bez zasięgu.
 *
 * Dopasowanie do drogi jest uproszczone: najbliższy odcinek w promieniu ~30 m,
 * z preferencją odcinków zgodnych z kierunkiem jazdy.
 */
class SpeedLimitProvider(ctx: Context) {

    private val offline: SQLiteDatabase? = run {
        val f = offlineFile(ctx)
        if (f.exists()) runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() else null
    }
    private val online = OnlineLimitCache.get(ctx)

    /** source: "osm" | "user" (Twoja poprawka) | "default:<typ drogi>"; wayId – droga OSM, jeśli znana */
    data class Limit(val kmh: Int, val source: String, val wayId: Long? = null)

    // starsze bazy offline nie mają kolumny way_id
    private val offlineHasWayId = offline?.let { db ->
        runCatching { db.rawQuery("PRAGMA table_info(segments)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "way_id") found = true
            found
        } }.getOrDefault(false)
    } ?: false

    /** @param bearing kierunek jazdy w stopniach (null gdy stoimy). */
    fun lookup(lat: Double, lon: Double, bearing: Float?): Limit? {
        if (offline == null) online.ensureAround(lat, lon, bearing)
        val a = offline?.let { best(it, lat, lon, bearing, offlineHasWayId) }
        val b = best(online.db, lat, lon, bearing, true)
        return listOfNotNull(a, b).minByOrNull { it.first }?.second
    }

    private fun best(db: SQLiteDatabase, lat: Double, lon: Double, bearing: Float?, hasWayId: Boolean): Pair<Double, Limit>? {
        val dLat = SEARCH_M / 111_320.0
        val dLon = SEARCH_M / (111_320.0 * cos(Math.toRadians(lat)))

        db.rawQuery(
            "SELECT lat1, lon1, lat2, lon2, maxspeed, highway, oneway, " + (if (hasWayId) "way_id" else "NULL") +
                " FROM segments WHERE maxlat >= ? AND minlat <= ? AND maxlon >= ? AND minlon <= ?",
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
                    val highway = c.getString(5) ?: "unclassified"
                    val wayId = if (c.isNull(7)) null else c.getLong(7)
                    val limit = online.overrideFor(wayId)?.let { Limit(it, "user", wayId) }
                        ?: if (!c.isNull(4)) Limit(c.getInt(4), "osm", wayId)
                        else defaultFor(highway)?.let { Limit(it, "default:$highway", wayId) }
                    if (limit != null) { bestScore = score; best = limit }
                }
            }
            return best?.let { bestScore to it }
        }
    }

    /**
     * Rzut punktu na najbliższy odcinek drogi (do wyświetlania śladu „po drogach”).
     * Nie pobiera nic z sieci – używa tylko danych już zapisanych w telefonie.
     */
    fun snap(lat: Double, lon: Double, bearing: Float?, maxM: Double): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        var bestScore = Double.MAX_VALUE
        for (db in listOfNotNull(offline, online.db)) {
            val dLat = maxM / 111_320.0
            val dLon = maxM / (111_320.0 * cos(Math.toRadians(lat)))
            db.rawQuery(
                """SELECT lat1, lon1, lat2, lon2, oneway FROM segments
                   WHERE maxlat >= ? AND minlat <= ? AND maxlon >= ? AND minlon <= ?""",
                arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val lat1 = c.getDouble(0); val lon1 = c.getDouble(1)
                    val lat2 = c.getDouble(2); val lon2 = c.getDouble(3)
                    val k = cos(Math.toRadians(lat)) * 111_320.0
                    val px = (lon - lon1) * k; val py = (lat - lat1) * 111_320.0
                    val sx = (lon2 - lon1) * k; val sy = (lat2 - lat1) * 111_320.0
                    val len2 = sx * sx + sy * sy
                    val t = if (len2 == 0.0) 0.0 else ((px * sx + py * sy) / len2).coerceIn(0.0, 1.0)
                    val dist = hypot(px - t * sx, py - t * sy)
                    if (dist > maxM) continue
                    var score = dist
                    if (bearing != null) {
                        val segBearing = bearingDeg(lat1, lon1, lat2, lon2)
                        var diff = abs(((bearing - segBearing + 540) % 360) - 180)
                        if (c.getInt(4) == 0) diff = min(diff, 180 - diff)
                        score += diff * 0.3
                    }
                    if (score < bestScore) {
                        bestScore = score
                        best = (lat1 + t * (lat2 - lat1)) to (lon1 + t * (lon2 - lon1))
                    }
                }
            }
        }
        return best
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

        fun offlineFile(ctx: Context) = File(ctx.getExternalFilesDir(null), DB_NAME)

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
