package pl.drivemonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pomiary osiągów liczone z GPS w trakcie nagrywania:
 *  - 0–100 km/h: start, gdy auto ruszy z miejsca, meta przy 100 km/h (bez zwalniania po drodze),
 *  - 100–150 km/h: od przekroczenia 100 do 150 przy ciągłym przyspieszaniu,
 *  - prędkość maksymalna sesji (zerowana przyciskiem „Reset”),
 *  - odcinki pomiarowe: własny start i meta; przejazd przez start uruchamia stoper, przez metę – zatrzymuje.
 * Czasy przecięcia progów są interpolowane między odczytami GPS (dokładność ok. ±0,2 s).
 */
object Performance {

    data class Run(val type: String, val seconds: Double, val timeMs: Long, val note: String? = null)
    data class Segment(val id: Long, val name: String, val sLat: Double, val sLon: Double, val eLat: Double, val eLon: Double)
    data class Pt(val lat: Double, val lon: Double)

    data class State(
        val speed: Double = 0.0,
        val vmax: Double = 0.0,
        val status0100: String = "Zatrzymaj się, aby zacząć",
        val running0100: Double? = null,
        val status100150: String = "Rozpędź się powyżej 100 km/h",
        val running100150: Double? = null,
        val segmentName: String? = null,
        val segmentElapsed: Double? = null,
        val newStart: Pt? = null,          // start nowego odcinka (czeka na metę)
        val runs: List<Run> = emptyList(),
        val segments: List<Segment> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var file: File? = null

    // stan wewnętrzny (wątek rejestratora)
    private var prevT = 0L; private var prevV = 0.0
    private var armed0100 = false; private var start0100: Double? = null
    private var start100150: Double? = null
    private var segActive: Segment? = null; private var segStartT = 0.0
    private var segStartMin = Double.MAX_VALUE; private var segStartArmed = false
    private var segEndMin = Double.MAX_VALUE; private var segEndT = 0.0
    private var segVmax = 0.0
    private var candidate: Segment? = null; private var candMin = Double.MAX_VALUE; private var candT = 0.0

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "performance.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val runs = o.optJSONArray("runs") ?: JSONArray()
            val segs = o.optJSONArray("segments") ?: JSONArray()
            _state.value = _state.value.copy(
                runs = List(runs.length()) { i ->
                    val r = runs.getJSONObject(i)
                    Run(r.getString("type"), r.getDouble("s"), r.getLong("t"), r.optString("note").ifEmpty { null })
                },
                segments = List(segs.length()) { i ->
                    val s = segs.getJSONObject(i)
                    Segment(s.getLong("id"), s.getString("name"), s.getDouble("sLat"), s.getDouble("sLon"), s.getDouble("eLat"), s.getDouble("eLon"))
                },
            )
        }
    }

    private fun persist() {
        val st = _state.value
        val o = JSONObject()
        o.put("runs", JSONArray().apply {
            st.runs.forEach { put(JSONObject().put("type", it.type).put("s", it.seconds).put("t", it.timeMs).put("note", it.note ?: "")) }
        })
        o.put("segments", JSONArray().apply {
            st.segments.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("sLat", it.sLat).put("sLon", it.sLon).put("eLat", it.eLat).put("eLon", it.eLon))
            }
        })
        runCatching { file?.writeText(o.toString()) }
    }

    private fun addRun(r: Run) {
        _state.value = _state.value.copy(runs = (listOf(r) + _state.value.runs).take(200))
        persist()
    }

    /** Czas (s od epoki) przecięcia prędkości [target] między dwoma odczytami. */
    private fun cross(t0: Double, v0: Double, t1: Double, v1: Double, target: Double): Double =
        if (v1 == v0) t1 else t0 + (t1 - t0) * ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)

    /** Wywoływane przez rejestrator dla każdego odczytu GPS. */
    @Synchronized
    fun onFix(timeMs: Long, speedKmh: Double, lat: Double, lon: Double) {
        val t = timeMs / 1000.0
        val pt = prevT / 1000.0
        val pv = prevV
        val first = prevT == 0L || timeMs - prevT > 5000
        prevT = timeMs; prevV = speedKmh
        var st = _state.value.copy(speed = speedKmh, vmax = maxOf(_state.value.vmax, speedKmh))

        // ---- 0–100 ----
        when {
            speedKmh < 2 -> { armed0100 = true; start0100 = null; st = st.copy(status0100 = "Gotowy – ruszaj!", running0100 = null) }
            first -> { armed0100 = false; start0100 = null }
            armed0100 && start0100 == null && pv < 2 -> start0100 = cross(pt, pv, t, speedKmh, 1.0)
        }
        start0100?.let { s ->
            when {
                speedKmh < pv - 3 -> { start0100 = null; armed0100 = false; st = st.copy(status0100 = "Przerwano (zwolniłeś). Zatrzymaj się i spróbuj ponownie.", running0100 = null) }
                speedKmh >= 100 -> {
                    val sec = cross(pt, pv, t, speedKmh, 100.0) - s
                    start0100 = null; armed0100 = false
                    if (sec in 1.5..60.0) addRun(Run("0–100", sec, timeMs))
                    st = st.copy(status0100 = "Wynik: %.2f s".format(sec), running0100 = null, runs = _state.value.runs)
                }
                else -> st = st.copy(status0100 = "Mierzę… %.0f km/h".format(speedKmh), running0100 = t - s)
            }
        }

        // ---- 100–150 ----
        if (!first && start100150 == null && pv < 100 && speedKmh >= 100 && speedKmh > pv) {
            start100150 = cross(pt, pv, t, speedKmh, 100.0)
        }
        start100150?.let { s ->
            when {
                speedKmh < pv - 3 || speedKmh < 95 -> { start100150 = null; st = st.copy(status100150 = "Przerwano (zwolniłeś)", running100150 = null) }
                speedKmh >= 150 -> {
                    val sec = cross(pt, pv, t, speedKmh, 150.0) - s
                    start100150 = null
                    if (sec in 1.0..90.0) addRun(Run("100–150", sec, timeMs))
                    st = st.copy(status100150 = "Wynik: %.2f s".format(sec), running100150 = null, runs = _state.value.runs)
                }
                else -> st = st.copy(status100150 = "Mierzę… %.0f km/h".format(speedKmh), running100150 = t - s)
            }
        }

        // ---- odcinki ----
        st = segments(st, t, speedKmh, lat, lon)
        _state.value = st
    }

    private fun segments(stIn: State, t: Double, v: Double, lat: Double, lon: Double): State {
        var st = stIn
        val active = segActive
        if (active == null) {
            // start: najbliższe podejście do linii startu (< 25 m) w ruchu
            for (seg in st.segments) {
                val d = DisplayTrack.distM(lat, lon, seg.sLat, seg.sLon)
                if (d < 25 && v > 5) {
                    if (candidate?.id != seg.id) { candidate = seg; candMin = d; candT = t }
                    else if (d < candMin) { candMin = d; candT = t }
                }
            }
            val c = candidate
            if (c != null) {
                val d = DisplayTrack.distM(lat, lon, c.sLat, c.sLon)
                if (d > candMin + 5 || d > 25) {     // minęliśmy start
                    segActive = c; segStartT = candT; segEndMin = Double.MAX_VALUE; segVmax = v
                    candidate = null; candMin = Double.MAX_VALUE
                }
            }
        } else {
            segVmax = maxOf(segVmax, v)
            val d = DisplayTrack.distM(lat, lon, active.eLat, active.eLon)
            if (d < 25 && d < segEndMin) { segEndMin = d; segEndT = t }
            val finished = segEndMin < 25 && (d > segEndMin + 5 || d > 25)
            when {
                finished -> {
                    val sec = segEndT - segStartT
                    addRun(Run("Odcinek: ${active.name}", sec, (segEndT * 1000).toLong(), "maks. %.0f km/h".format(segVmax)))
                    segActive = null
                    st = st.copy(segmentName = null, segmentElapsed = null, runs = _state.value.runs)
                }
                t - segStartT > 3600 -> { segActive = null; st = st.copy(segmentName = null, segmentElapsed = null) }
                else -> st = st.copy(segmentName = active.name, segmentElapsed = t - segStartT)
            }
        }
        return st
    }

    // ---------- akcje użytkownika (wątek główny) ----------

    @Synchronized
    fun resetSession() {
        start0100 = null; start100150 = null; segActive = null; candidate = null
        _state.value = _state.value.copy(
            vmax = 0.0, status0100 = "Zatrzymaj się, aby zacząć", running0100 = null,
            status100150 = "Rozpędź się powyżej 100 km/h", running100150 = null,
            segmentName = null, segmentElapsed = null,
        )
    }

    @Synchronized
    fun clearRuns() { _state.value = _state.value.copy(runs = emptyList()); persist() }

    fun setNewStart(lat: Double, lon: Double) { _state.value = _state.value.copy(newStart = Pt(lat, lon)) }

    fun cancelNewSegment() { _state.value = _state.value.copy(newStart = null) }

    @Synchronized
    fun finishNewSegment(lat: Double, lon: Double) {
        val s = _state.value.newStart ?: return
        val n = _state.value.segments.size + 1
        val seg = Segment(System.currentTimeMillis(), "Odcinek $n", s.lat, s.lon, lat, lon)
        _state.value = _state.value.copy(newStart = null, segments = _state.value.segments + seg)
        persist()
    }

    @Synchronized
    fun deleteSegment(seg: Segment) {
        if (segActive?.id == seg.id) segActive = null
        _state.value = _state.value.copy(segments = _state.value.segments.filter { it.id != seg.id })
        persist()
    }
}
