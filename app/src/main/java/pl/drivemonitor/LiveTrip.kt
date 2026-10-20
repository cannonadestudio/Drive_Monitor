package pl.drivemonitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bieżący stan nagrywanej trasy – rejestrator publikuje go co sekundę, ekran główny pokazuje na żywo. */
object LiveTrip {
    data class State(
        val startMs: Long,
        val speedKmh: Double,
        val limitKmh: Int?,
        val distanceM: Double,
        val okM: Double,          // dystans w limicie
        val overM: Double,        // dystans ponad limitem
        val altitudeM: Double?,
        val gpsOk: Boolean,       // czy jest świeży, dokładny fix
        val limitIsDefault: Boolean = false,   // limit z typu drogi, nie ze znaku w OSM
        val maxSpeedKmh: Double = 0.0,
        val harshBrakes: Int = 0,
        val noLimitM: Double = 0.0,            // dystans bez znanego limitu
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val wayId: Long? = null,               // droga OSM, na której jesteśmy (do zgłaszania limitu)
        val limitIsUser: Boolean = false,      // limit z Twojej poprawki
        val fixTimeMs: Long = 0L,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    internal fun update(s: State?) { _state.value = s }

    /** Ślad trwającej jazdy (co ≥ 8 m) – do żywej mapy na ekranie głównym. */
    private val _track = MutableStateFlow<List<ThumbPoint>>(emptyList())
    val track: StateFlow<List<ThumbPoint>> = _track

    internal fun resetTrack() { _track.value = emptyList() }

    internal fun appendTrack(lat: Double, lon: Double, cat: Int) {
        val last = _track.value.lastOrNull()
        if (last != null && DisplayTrack.distM(last.lat, last.lon, lat, lon) < 8 && last.cat == cat) return
        _track.value = _track.value + ThumbPoint(lon, lat, cat)
    }
}

/** Czas jako „1 h 05 min 12 s”, „18 min 05 s” albo „42 s”. */
fun formatDuration(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return when {
        h > 0 -> "%d h %02d min %02d s".format(h, m, sec)
        m > 0 -> "%d min %02d s".format(m, sec)
        else -> "$sec s"
    }
}
