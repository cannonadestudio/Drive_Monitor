package pl.drivemonitor

import org.json.JSONObject
import kotlin.math.max

/**
 * Statystyki bezpieczeństwa liczone na bieżąco podczas jazdy.
 * Czas i dystans przekroczeń w progach: >0, >10, >20, >30 km/h ponad limit.
 */
class TripStats {
    var startMs = 0L; private set
    var endMs = 0L; private set
    var distanceM = 0.0; private set
    var maxSpeedKmh = 0.0; private set

    var timeWithLimitS = 0.0; private set          // czas, dla którego znaliśmy limit
    val overTimeS = DoubleArray(THRESHOLDS.size)   // czas powyżej progu
    val overDistM = DoubleArray(THRESHOLDS.size)
    var maxOverKmh = 0.0; private set
    var harshBrakes = 0; private set
    var harshAccels = 0; private set

    private var prev: TrackPoint? = null
    var pointCount = 0; private set

    fun add(p: TrackPoint) {
        pointCount++
        if (startMs == 0L) startMs = p.timeMs
        endMs = p.timeMs
        maxSpeedKmh = max(maxSpeedKmh, p.speedKmh)

        val last = prev
        prev = p
        if (last == null) return
        val dt = (p.timeMs - last.timeMs) / 1000.0
        if (dt <= 0 || dt > 10) return              // przerwa w sygnale – nie zliczamy

        val segM = (last.speedKmh + p.speedKmh) / 2 / 3.6 * dt
        distanceM += segM

        // przyspieszenie z prędkości GPS (m/s²); progi typowe dla telematyki
        val acc = (p.speedKmh - last.speedKmh) / 3.6 / dt
        if (acc <= HARSH_BRAKE) harshBrakes++
        if (acc >= HARSH_ACCEL) harshAccels++

        val limit = p.limitKmh ?: return
        timeWithLimitS += dt
        val over = p.speedKmh - limit
        maxOverKmh = max(maxOverKmh, over)
        THRESHOLDS.forEachIndexed { i, th ->
            if (over > th) { overTimeS[i] += dt; overDistM[i] += segM }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("startMs", startMs); put("endMs", endMs)
        put("distanceKm", distanceM / 1000)
        put("durationMin", (endMs - startMs) / 60000.0)
        put("maxSpeedKmh", maxSpeedKmh)
        put("limitCoveragePct", if (endMs > startMs) timeWithLimitS * 100_000 / (endMs - startMs) else 0.0)
        put("maxOverKmh", maxOverKmh)
        put("harshBrakes", harshBrakes); put("harshAccels", harshAccels)
        THRESHOLDS.forEachIndexed { i, th ->
            put("overPct_$th", if (timeWithLimitS > 0) overTimeS[i] * 100 / timeWithLimitS else 0.0)
            put("overKm_$th", overDistM[i] / 1000)
        }
    }

    companion object {
        val THRESHOLDS = intArrayOf(0, 10, 20, 30)
        const val HARSH_BRAKE = -3.5   // m/s²
        const val HARSH_ACCEL = 3.0
    }
}
