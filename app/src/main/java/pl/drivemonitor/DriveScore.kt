package pl.drivemonitor

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Punktacja za bezpieczną jazdę – liczona z zapisanych statystyk tras (nic dodatkowego nie jest zbierane).
 *
 *  Ocena trasy 0–100:
 *    100 minus kary za udział dystansu ponad limitem (im większe przekroczenie, tym większa kara)
 *    i za gwałtowne hamowania / przyspieszenia (liczone na 10 km).
 *  Punkty za trasę: 1 pkt za każdy km w limicie (½ pkt za km bez znanego limitu) × mnożnik za ocenę
 *    (95+ ×2, 85+ ×1,5, 70+ ×1, poniżej ×0,5) + 10 pkt premii za czystą trasę (≥ 3 km, ocena 95+).
 *  Poziomy według sumy punktów, odznaki za konkretne osiągnięcia.
 */
object DriveScore {

    data class TripScore(
        val startMs: Long, val km: Double, val score: Int, val stars: Int, val points: Int,
        val clean: Boolean, val harshBrakes: Int, val over20Km: Double,
    )

    data class Level(val n: Int, val name: String, val from: Int, val to: Int?)

    data class Badge(
        val id: String, val icon: String, val name: String, val how: String,
        val unlocked: Boolean, val progress: String? = null,
    )

    data class Summary(
        val trips: List<TripScore>,          // najnowsze pierwsze
        val points: Int,
        val level: Level,
        val streak: Int,                     // tyle ostatnich tras z rzędu z oceną 85+
        val avgScore30: Int?,                // średnia ocena z 30 dni (ważona km)
        val badges: List<Badge>,
    )

    val LEVELS = listOf(
        0 to "Kursant", 100 to "Kierowca", 300 to "Pewny kierowca", 700 to "Wzorowy kierowca",
        1500 to "Mistrz kierownicy", 3000 to "Kapitan drogi", 6000 to "Legenda szos",
    )

    fun levelFor(points: Int): Level {
        val i = LEVELS.indexOfLast { points >= it.first }.coerceAtLeast(0)
        return Level(i + 1, LEVELS[i].second, LEVELS[i].first, LEVELS.getOrNull(i + 1)?.first)
    }

    fun scoreTrip(js: JSONObject): TripScore {
        val cats = TripStats.kmByCategory(js)   // brak limitu, w limicie, +0–10, +10–20, +20–30, >+30
        val km = js.optDouble("distanceKm", 0.0)
        val withLimit = cats[1] + cats[2] + cats[3] + cats[4] + cats[5]
        fun pct(x: Double) = if (withLimit > 0.2) x / withLimit * 100 else 0.0
        val speedPenalty = pct(cats[2]) * 0.3 + pct(cats[3]) * 1.0 + pct(cats[4]) * 2.0 + pct(cats[5]) * 3.0
        val brakes = js.optInt("harshBrakes"); val accels = js.optInt("harshAccels")
        val smoothPenalty = ((4.0 * brakes + 2.0 * accels) * 10 / maxOf(km, 10.0)).coerceAtMost(30.0)
        val score = (100 - speedPenalty - smoothPenalty).coerceIn(0.0, 100.0).roundToInt()
        val stars = when { score >= 95 -> 3; score >= 85 -> 2; score >= 70 -> 1; else -> 0 }
        val mult = when { score >= 95 -> 2.0; score >= 85 -> 1.5; score >= 70 -> 1.0; else -> 0.5 }
        val clean = km >= 3 && score >= 95
        val points = ((cats[1] + cats[0] * 0.5) * mult).roundToInt() + if (clean) 10 else 0
        return TripScore(js.optLong("startMs"), km, score, stars, points, clean, brakes, cats[4] + cats[5])
    }

    fun summary(trips: List<TripRepository.Trip>): Summary {
        val scored = trips.filter { !it.recording && it.stats != null && it.stats.optDouble("distanceKm", 0.0) >= 0.3 }
            .map { scoreTrip(it.stats!!) }
            .sortedByDescending { it.startMs }
        val chrono = scored.reversed()
        val points = scored.sumOf { it.points }
        val streak = scored.takeWhile { it.score >= 85 }.size

        val since = System.currentTimeMillis() - 30L * 86_400_000
        val recent = scored.filter { it.startMs >= since && it.km > 0 }
        val avg30 = if (recent.isEmpty()) null else (recent.sumOf { it.score * it.km } / recent.sumOf { it.km }).roundToInt()

        // pomocnicze do odznak
        val okKm = trips.filter { !it.recording }.mapNotNull { it.stats }.sumOf { TripStats.kmByCategory(it)[1] }
        fun maxRun(pred: (TripScore) -> Boolean): Int {
            var best = 0; var cur = 0
            chrono.forEach { if (pred(it)) { cur++; best = maxOf(best, cur) } else cur = 0 }
            return best
        }
        val bestGoodRun = maxRun { it.score >= 85 }
        val bestSmoothRun = maxRun { it.harshBrakes == 0 }
        val zone = ZoneId.systemDefault()
        val byDay = chrono.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        var bestDays = 0; var curDays = 0; var prevDay: LocalDate? = null
        byDay.keys.sorted().forEach { d ->
            val good = byDay[d]!!.all { it.score >= 85 }
            curDays = if (good && prevDay != null && d == prevDay!!.plusDays(1)) curDays + 1 else if (good) 1 else 0
            bestDays = maxOf(bestDays, curDays)
            prevDay = d
        }
        val longClean = scored.any { it.km >= 50 && it.score >= 90 && it.over20Km < 0.05 }
        val perfect = scored.any { it.score == 100 && it.km >= 5 }

        fun prog(cur: Number, goal: Number, unit: String = "") = "${cur} / ${goal}$unit"
        val badges = listOf(
            Badge("first", "🚗", "Pierwsza jazda", "Zakończ pierwszą trasę.", scored.isNotEmpty()),
            Badge("clean", "✨", "Czysta trasa", "Trasa min. 3 km z oceną 95+.", scored.any { it.clean }),
            Badge("perfect", "💯", "Perfekcja", "Trasa min. 5 km z oceną 100.", perfect),
            Badge("hattrick", "🎩", "Hat-trick", "3 trasy z rzędu z oceną 85+.", bestGoodRun >= 3, prog(minOf(bestGoodRun, 3), 3)),
            Badge("ten", "🔟", "Dziesiątka", "10 tras z rzędu z oceną 85+.", bestGoodRun >= 10, prog(minOf(bestGoodRun, 10), 10)),
            Badge("smooth", "🪶", "Delikatna stopa", "10 tras z rzędu bez gwałtownego hamowania.", bestSmoothRun >= 10, prog(minOf(bestSmoothRun, 10), 10)),
            Badge("week", "📅", "Wzorowy tydzień", "7 dni jazdy z rzędu, każda trasa z oceną 85+.", bestDays >= 7, prog(minOf(bestDays, 7), 7, " dni")),
            Badge("long", "🛣️", "Długi dystans", "Trasa min. 50 km z oceną 90+ i bez przekroczeń o ponad 20 km/h.", longClean),
            Badge("km100", "🥉", "100 km w limicie", "Przejedź łącznie 100 km w limicie.", okKm >= 100, prog(minOf(okKm, 100.0).roundToInt(), 100, " km")),
            Badge("km1000", "🥈", "1000 km w limicie", "Przejedź łącznie 1000 km w limicie.", okKm >= 1000, prog(minOf(okKm, 1000.0).roundToInt(), 1000, " km")),
            Badge("km5000", "🥇", "5000 km w limicie", "Przejedź łącznie 5000 km w limicie.", okKm >= 5000, prog(minOf(okKm, 5000.0).roundToInt(), 5000, " km")),
        )
        return Summary(scored, points, levelFor(points), streak, avg30, badges)
    }

    // ---- nowe odznaki (do pokazania na ekranie głównym) ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("rewards", Context.MODE_PRIVATE)

    /** Odznaki zdobyte, a jeszcze nieobejrzane. Przy pierwszym uruchomieniu wszystkie dotychczasowe uznajemy za obejrzane. */
    fun newBadges(ctx: Context, s: Summary): List<Badge> {
        val p = prefs(ctx)
        val unlocked = s.badges.filter { it.unlocked }
        if (!p.contains("seen")) { p.edit().putStringSet("seen", unlocked.map { it.id }.toSet()).apply(); return emptyList() }
        val seen = p.getStringSet("seen", emptySet()) ?: emptySet()
        return unlocked.filter { it.id !in seen }
    }

    fun markSeen(ctx: Context, s: Summary) {
        prefs(ctx).edit().putStringSet("seen", s.badges.filter { it.unlocked }.map { it.id }.toSet()).apply()
    }

    fun starsText(n: Int) = "★".repeat(n) + "☆".repeat(3 - n)
}
