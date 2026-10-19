package pl.drivemonitor

import android.content.Context
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Trasa przygotowana do wyświetlenia: punkty po drogach, czas, dystans, wysokość, kategorie prędkości. */
class TripData private constructor(
    val pts: List<TrackPoint>,
    val stats: JSONObject?,
) {
    val startMs: Long = pts.firstOrNull()?.timeMs ?: 0L
    /** sekundy od startu */
    val t = DoubleArray(pts.size) { (pts[it].timeMs - startMs) / 1000.0 }
    /** dystans od startu w metrach */
    val d = DoubleArray(pts.size)
    val cats = IntArray(pts.size) { TripRepository.category(pts[it].speedKmh, pts[it].limitKmh) }
    /** wygładzona wysokość albo null, gdy GPS jej nie podawał */
    val ele: DoubleArray?
    val catKm = DoubleArray(6)
    var gain = 0.0; private set
    var loss = 0.0; private set

    init {
        for (i in 1 until pts.size) {
            d[i] = d[i - 1] + DisplayTrack.distM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            catKm[cats[i]] += (d[i] - d[i - 1]) / 1000
        }
        val withEle = pts.count { it.altitude != null }
        ele = if (pts.isNotEmpty() && withEle > pts.size * 0.8) {
            var last = pts.first { it.altitude != null }.altitude!!
            val raw = DoubleArray(pts.size) { i -> (pts[i].altitude ?: last).also { last = it } }
            DoubleArray(raw.size) { i ->
                var s = 0.0; var n = 0
                for (j in max(0, i - 5)..min(raw.size - 1, i + 5)) { s += raw[j]; n++ }
                s / n
            }
        } else null
        ele?.let { e ->
            var ref = e[0]
            for (v in e) {
                if (v - ref > 3) { gain += v - ref; ref = v } else if (ref - v > 3) { loss += ref - v; ref = v }
            }
        }
    }

    val totalT get() = if (t.isEmpty()) 0.0 else max(t.last(), 0.001)
    val totalD get() = if (d.isEmpty()) 0.0 else max(d.last(), 0.001)
    val maxSpeed: Double get() = stats?.optDouble("maxSpeedKmh")?.takeIf { !it.isNaN() } ?: pts.maxOfOrNull { it.speedKmh } ?: 0.0
    val overKm get() = catKm[2] + catKm[3] + catKm[4] + catKm[5]

    data class State(val i: Int, val lat: Double, val lon: Double, val speed: Double, val limit: Int?, val ele: Double?, val dist: Double)

    fun stateAt(time: Double): State {
        if (pts.size < 2) return State(0, pts[0].lat, pts[0].lon, pts[0].speedKmh, pts[0].limitKmh, ele?.get(0), 0.0)
        var lo = 0; var hi = t.size - 1
        if (time <= t[0]) hi = 1
        else if (time >= t[hi]) lo = hi - 1
        else while (hi - lo > 1) { val m = (lo + hi) / 2; if (t[m] <= time) lo = m else hi = m }
        val span = t[hi] - t[lo]
        val f = if (span > 0) ((time - t[lo]) / span).coerceIn(0.0, 1.0) else 0.0
        val a = pts[lo]; val b = pts[hi]
        fun lerp(x: Double, y: Double) = x + (y - x) * f
        return State(
            lo, lerp(a.lat, b.lat), lerp(a.lon, b.lon), lerp(a.speedKmh, b.speedKmh),
            if (f < 0.5) a.limitKmh else b.limitKmh,
            ele?.let { lerp(it[lo], it[hi]) }, lerp(d[lo], d[hi]),
        )
    }

    /** Punkt ~[meters] dalej po trasie – do płynnego kierunku kamery. */
    fun pointAhead(i: Int, dist: Double, meters: Double): TrackPoint {
        var j = i
        while (j < pts.size - 1 && d[j] < dist + meters) j++
        return pts[j]
    }

    /** Odcinki trasy pogrupowane wg kategorii prędkości (właściwość "cat" = "0".."5"). */
    fun routeFeatures(): FeatureCollection {
        val features = ArrayList<Feature>()
        var cur = ArrayList<Point>()
        var curCat = -1
        for (i in 1 until pts.size) {
            if (cats[i] != curCat) {
                if (cur.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(cur)).also { it.addStringProperty("cat", curCat.toString()) }
                cur = arrayListOf(Point.fromLngLat(pts[i - 1].lon, pts[i - 1].lat))
                curCat = cats[i]
            }
            cur += Point.fromLngLat(pts[i].lon, pts[i].lat)
        }
        if (cur.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(cur)).also { it.addStringProperty("cat", curCat.toString()) }
        return FeatureCollection.fromFeatures(features)
    }

    fun bounds(): LatLngBounds? {
        if (pts.size < 2) return null
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(LatLng(it.lat, it.lon)) }
        return runCatching { b.build() }.getOrNull()
    }

    companion object {
        /** Wołać poza wątkiem głównym. */
        fun load(ctx: Context, gpx: File): TripData {
            val statsFile = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")
            val stats = if (statsFile.exists()) runCatching { JSONObject(statsFile.readText()) }.getOrNull() else null
            return TripData(DisplayTrack.prepare(ctx, GpxParser.parse(gpx)), stats)
        }
    }
}
