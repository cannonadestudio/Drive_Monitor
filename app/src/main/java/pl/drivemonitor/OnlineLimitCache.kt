package pl.drivemonitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Ograniczenia prędkości pobierane automatycznie z OpenStreetMap (Overpass API)
 * dla kafelków ~3 × 2 km wokół auta i zapisywane w telefonie.
 * Raz pobrany obszar działa potem offline. Dane odświeżane po 90 dniach.
 *
 * Pobieramy kafelek, w którym jest auto, oraz kafelek ~1,5 km przed nim,
 * żeby limity były gotowe, zanim tam dojedziemy.
 */
class OnlineLimitCache private constructor(ctx: Context) {

    private val helper = object : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE segments (
                    tile TEXT, lat1 REAL, lon1 REAL, lat2 REAL, lon2 REAL,
                    minlat REAL, maxlat REAL, minlon REAL, maxlon REAL,
                    maxspeed INTEGER, highway TEXT, oneway INTEGER, way_id INTEGER)"""
            )
            db.execSQL("CREATE INDEX idx_bbox ON segments (minlat, minlon)")
            db.execSQL("CREATE INDEX idx_tile ON segments (tile)")
            db.execSQL("CREATE TABLE tiles (tile TEXT PRIMARY KEY, fetched INTEGER)")
            createOverrides(db)
        }
        // v2: identyfikator drogi OSM (do poprawek limitu) – dane dróg pobierzemy od nowa
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS segments")
            db.execSQL("DROP TABLE IF EXISTS tiles")
            db.execSQL("DROP TABLE IF EXISTS overrides")
            onCreate(db)
        }
        private fun createOverrides(db: SQLiteDatabase) = db.execSQL(
            "CREATE TABLE IF NOT EXISTS overrides (way_id INTEGER PRIMARY KEY, maxspeed INTEGER, ts INTEGER)"
        )
    }.apply { setWriteAheadLoggingEnabled(true) }

    /** Poprawki ograniczeń wprowadzone przez użytkownika: id drogi OSM -> km/h. */
    private val overrides = ConcurrentHashMap<Long, Int>()

    fun overrideFor(wayId: Long?): Int? = wayId?.let { overrides[it] }

    fun setOverride(wayId: Long, kmh: Int) {
        db.execSQL("INSERT OR REPLACE INTO overrides VALUES (?, ?, ?)", arrayOf(wayId, kmh, System.currentTimeMillis()))
        overrides[wayId] = kmh
    }

    fun removeOverride(wayId: Long) {
        db.execSQL("DELETE FROM overrides WHERE way_id = ?", arrayOf(wayId))
        overrides.remove(wayId)
    }

    fun overrideCount() = overrides.size

    val db: SQLiteDatabase get() = helper.writableDatabase

    private val io = Executors.newSingleThreadExecutor()   // Overpass prosi o zapytania po kolei
    private val fresh = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    init {
        db.rawQuery("SELECT way_id, maxspeed FROM overrides", null).use {
            while (it.moveToNext()) overrides[it.getLong(0)] = it.getInt(1)
        }
        val minFetched = System.currentTimeMillis() - MAX_AGE_MS
        db.rawQuery("SELECT tile FROM tiles WHERE fetched > ?", arrayOf(minFetched.toString())).use {
            while (it.moveToNext()) fresh += it.getString(0)
        }
    }

    fun tileCount(): Int = fresh.size

    /** Nie blokuje – zleca pobranie brakujących kafelków w tle. */
    fun ensureAround(lat: Double, lon: Double, bearing: Float?) {
        request(tileKey(lat, lon))
        if (bearing != null) {
            val b = Math.toRadians(bearing.toDouble())
            val aLat = lat + AHEAD_M * cos(b) / 111_320.0
            val aLon = lon + AHEAD_M * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
            request(tileKey(aLat, aLon))
        }
    }

    private fun request(key: String) {
        if (key in fresh || key in inFlight) return
        failedAt[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return }
        inFlight += key
        io.execute {
            try {
                download(key)
                fresh += key
                failedAt.remove(key)
            } catch (e: Exception) {
                Log.w(TAG, "Nie pobrano kafelka $key: ${e.message}")
                failedAt[key] = System.currentTimeMillis()
            } finally {
                inFlight -= key
            }
        }
    }

    private fun download(key: String) {
        val (ix, iy) = key.split(':').map { it.toInt() }
        val s = ix * TILE_LAT; val n = s + TILE_LAT
        val w = iy * TILE_LON; val e = w + TILE_LON
        val query = """[out:json][timeout:25];
            way["highway"~"^($HIGHWAYS)$"]($s,$w,$n,$e);
            out tags geom;"""

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 40_000
        conn.setRequestProperty("User-Agent", "DriveMonitor/0.2 (Android; personal speed log)")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val elements = json.getJSONArray("elements")

        val db = db
        db.beginTransaction()
        try {
            db.delete("segments", "tile = ?", arrayOf(key))
            val stmt = db.compileStatement(
                "INSERT INTO segments VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            var count = 0
            for (i in 0 until elements.length()) {
                val way = elements.getJSONObject(i)
                val tags = way.optJSONObject("tags") ?: continue
                val geom = way.optJSONArray("geometry") ?: continue
                val highway = tags.optString("highway")
                val wayId = way.optLong("id")
                val speed = parseMaxspeed(tags)
                val onewayTag = tags.optString("oneway")
                val reverse = onewayTag == "-1"
                val oneway = onewayTag in setOf("yes", "1", "-1") || highway.startsWith("motorway")

                for (j in 0 until geom.length() - 1) {
                    var a = geom.getJSONObject(j); var b = geom.getJSONObject(j + 1)
                    if (reverse) { val t = a; a = b; b = t }
                    val lat1 = a.getDouble("lat"); val lon1 = a.getDouble("lon")
                    val lat2 = b.getDouble("lat"); val lon2 = b.getDouble("lon")
                    stmt.clearBindings()
                    stmt.bindString(1, key)
                    stmt.bindDouble(2, lat1); stmt.bindDouble(3, lon1)
                    stmt.bindDouble(4, lat2); stmt.bindDouble(5, lon2)
                    stmt.bindDouble(6, min(lat1, lat2)); stmt.bindDouble(7, max(lat1, lat2))
                    stmt.bindDouble(8, min(lon1, lon2)); stmt.bindDouble(9, max(lon1, lon2))
                    if (speed != null) stmt.bindLong(10, speed.toLong()) else stmt.bindNull(10)
                    stmt.bindString(11, highway)
                    stmt.bindLong(12, if (oneway) 1 else 0)
                    stmt.bindLong(13, wayId)
                    stmt.executeInsert()
                    count++
                }
            }
            stmt.close()
            db.execSQL(
                "INSERT OR REPLACE INTO tiles VALUES (?, ?)",
                arrayOf(key, System.currentTimeMillis())
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "Kafelek $key: $count odcinków")
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val TAG = "OnlineLimits"
        private const val DB_NAME = "limits_cache.sqlite"
        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val TILE_LAT = 0.03          // ~3,3 km
        private const val TILE_LON = 0.03          // ~2 km w Polsce
        private const val AHEAD_M = 1500.0
        private const val RETRY_MS = 30_000L
        private const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
        // drogi serwisowe (parkingi, stacje) pomijamy – psułyby dopasowanie obok głównej drogi
        private const val HIGHWAYS = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|" +
            "secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street"

        fun tileKey(lat: Double, lon: Double) =
            "${floor(lat / TILE_LAT).toInt()}:${floor(lon / TILE_LON).toInt()}"

        @Volatile private var instance: OnlineLimitCache? = null
        fun get(ctx: Context): OnlineLimitCache =
            instance ?: synchronized(this) { instance ?: OnlineLimitCache(ctx).also { instance = it } }

        private val IMPLICIT = mapOf(
            "PL:urban" to 50, "PL:rural" to 90, "PL:expressway" to 120, "PL:motorway" to 140,
            "PL:living_street" to 20, "PL:zone30" to 30, "PL:zone20" to 20, "walk" to 10,
        )

        /** Tak samo jak w tools/build_speed_db.py. */
        fun parseMaxspeed(tags: JSONObject): Int? {
            val raw = sequenceOf("maxspeed", "maxspeed:type", "source:maxspeed")
                .map { tags.optString(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            IMPLICIT[raw]?.let { return it }
            Regex("""^(\d+)\s*(mph)?$""").find(raw)?.let { m ->
                val v = m.groupValues[1].toInt()
                return if (m.groupValues[2].isNotEmpty()) (v * 1.609).roundToInt() else v
            }
            return Regex("""^(\d+)""").find(raw)?.groupValues?.get(1)?.toInt()
        }
    }
}
