package pl.drivemonitor

import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Przygotowanie śladu do wyświetlenia (plik GPX zostaje nietknięty):
 *  1. postoje – drgania GPS na światłach dają „gwiazdki”; punkty bliżej niż 12 m
 *     od poprzedniego przy prędkości < 4 km/h są pomijane,
 *  2. dopasowanie do dróg – punkt przesuwany na najbliższy odcinek drogi z bazy
 *     ograniczeń (te same dane OSM, pobrane podczas jazdy), jeśli jest bliżej niż 25 m.
 */
object DisplayTrack {

    fun prepare(ctx: Context, raw: List<TrackPoint>): List<TrackPoint> {
        if (raw.size < 2) return raw
        val kept = ArrayList<TrackPoint>(raw.size)
        for (p in raw) {
            val last = kept.lastOrNull()
            if (last != null && p.speedKmh < 4 && distM(last.lat, last.lon, p.lat, p.lon) < 12) continue
            kept += p
        }
        if (kept.last() !== raw.last()) kept += raw.last()

        val provider = runCatching { SpeedLimitProvider(ctx) }.getOrNull() ?: return kept
        return kept.mapIndexed { i, p ->
            val a = kept[maxOf(0, i - 1)]; val b = kept[minOf(kept.size - 1, i + 1)]
            val bearing = if (distM(a.lat, a.lon, b.lat, b.lon) > 3) bearingDeg(a.lat, a.lon, b.lat, b.lon).toFloat() else null
            val snap = runCatching { provider.snap(p.lat, p.lon, bearing, SNAP_M) }.getOrNull()
            if (snap != null) p.copy(lat = snap.first, lon = snap.second) else p
        }
    }

    private const val SNAP_M = 25.0

    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = cos(Math.toRadians(lat1)) * 111_320.0
        return hypot((lon2 - lon1) * k, (lat2 - lat1) * 111_320.0)
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = cos(Math.toRadians(lat1))
        return (Math.toDegrees(atan2((lon2 - lon1) * k, lat2 - lat1)) + 360) % 360
    }
}
