package pl.drivemonitor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * „Powrót tą samą drogą”: odwraca zapisaną trasę dojazdu i otwiera nawigację Google Maps
 * z punktami pośrednimi rozłożonymi wzdłuż tej trasy – Google musi przejechać przez nie,
 * więc prowadzi z powrotem tymi samymi drogami. Celem jest miejsce, z którego wyruszyłeś.
 *
 * Google Maps przyjmuje w linku najwyżej 9 punktów pośrednich (na telefonie pokazuje je jako przystanki).
 * Krótkie postoje po drodze (tankowanie, kawa) dzielą dojazd na kilka tras – łączymy je,
 * jeśli przerwa była krótsza niż 90 min, a kolejna trasa zaczęła się tam, gdzie skończyła poprzednia.
 */
object ReturnRoute {

    data class Plan(
        val destination: TrackPoint,
        val waypoints: List<TrackPoint>,
        val km: Double,
        val trips: List<TripRepository.Trip>,   // trasy dojazdu (od najstarszej)
    )

    private const val MAX_WAYPOINTS = 9
    private const val CHAIN_GAP_MS = 90 * 60_000L
    private const val CHAIN_DIST_M = 500.0

    /** Ostatni zakończony dojazd (z krótkimi postojami po drodze), od najstarszej trasy. */
    fun outbound(trips: List<TripRepository.Trip>): List<TripRepository.Trip> {
        val done = trips.filter { !it.recording && it.stats != null }      // najnowsze pierwsze
        val firstIdx = done.indexOfFirst { it.stats!!.optDouble("distanceKm", 0.0) >= 1.0 }
        if (firstIdx < 0) return emptyList()
        val chain = arrayListOf(done[firstIdx])
        var i = firstIdx + 1
        while (i < done.size && chain.size < 6) {
            val newer = chain.first(); val older = done[i]
            val gap = newer.stats!!.optLong("startMs") - older.stats!!.optLong("endMs")
            if (gap !in 0..CHAIN_GAP_MS) break
            val olderEnd = GpxParser.parse(older.gpx).lastOrNull() ?: break
            val newerStart = GpxParser.parse(newer.gpx).firstOrNull() ?: break
            if (DisplayTrack.distM(olderEnd.lat, olderEnd.lon, newerStart.lat, newerStart.lon) > CHAIN_DIST_M) break
            chain.add(0, older)
            i++
        }
        return chain
    }

    /**
     * Plan powrotu dla podanych tras dojazdu (od najstarszej).
     * [hereLat]/[hereLon] – bieżąca pozycja: część trasy, którą już przejechałeś w drodze powrotnej, jest pomijana.
     */
    fun plan(trips: List<TripRepository.Trip>, hereLat: Double?, hereLon: Double?): Plan? {
        val pts = trips.flatMap { GpxParser.parse(it.gpx) }
        if (pts.size < 2) return null
        var route = pts.reversed()

        // pomiń początek odwróconej trasy aż do punktu najbliższego bieżącej pozycji (do 3 km)
        if (hereLat != null && hereLon != null) {
            var best = -1; var bestD = 3000.0
            route.forEachIndexed { i, p ->
                val d = DisplayTrack.distM(hereLat, hereLon, p.lat, p.lon)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best > 0) route = route.drop(best)
        }
        if (route.size < 2) return null

        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + DisplayTrack.distM(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        val total = cum.last()
        val dest = route.last()
        if (total < 800) return Plan(dest, emptyList(), total / 1000, trips)

        // liczba punktów: ok. 1 na 4 km, od 3 do 9
        val n = (total / 4000).roundToInt().coerceIn(3, MAX_WAYPOINTS)
        val window = total / (n + 1) / 3
        val chosen = ArrayList<TrackPoint>()
        for (k in 1..n) {
            val target = total * k / (n + 1)
            var bestI = -1; var bestCost = Double.MAX_VALUE
            for (i in route.indices) {
                val off = abs(cum[i] - target)
                if (off > window) continue
                if (cum[i] < 400 || total - cum[i] < 400) continue
                val v = route[i].speedKmh
                // najlepiej zwykła droga w ruchu: nie postój na skrzyżowaniu, nie autostrada (dwie jezdnie – punkt mógłby trafić na przeciwną)
                val penalty = when {
                    v.isNaN() -> 300.0
                    v < 15 -> 800.0
                    v > 100 -> 1500.0
                    else -> 0.0
                }
                val cost = off + penalty
                if (cost < bestCost) { bestCost = cost; bestI = i }
            }
            if (bestI >= 0) {
                val p = route[bestI]
                if (chosen.none { DisplayTrack.distM(it.lat, it.lon, p.lat, p.lon) < 300 }) chosen += p
            }
        }
        return Plan(dest, chosen, total / 1000, trips)
    }

    private fun ll(p: TrackPoint) = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)

    fun mapsUri(plan: Plan): Uri {
        val b = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", ll(plan.destination))
            .appendQueryParameter("travelmode", "driving")
            .appendQueryParameter("dir_action", "navigate")
        if (plan.waypoints.isNotEmpty()) b.appendQueryParameter("waypoints", plan.waypoints.joinToString("|") { ll(it) })
        return b.build()
    }

    /** Otwiera Google Maps (aplikację, a gdy jej brak – przeglądarkę). */
    fun open(ctx: Context, plan: Plan) {
        val uri = mapsUri(plan)
        val app = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
