package pl.drivemonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pomiary osiągów liczone z GPS w trakcie nagrywania.
 *
 * Przyspieszenie (0–100, 100–150) działa krok po kroku:
 *   START → „Zatrzymaj auto” / „Zwolnij” → „Gotowy – ruszaj!” → stoper rusza sam → meta → raport.
 * Czasy przecięcia progów są interpolowane między odczytami GPS (dokładność ok. ±0,2 s).
 * Przyspieszenie w g: z czujników telefonu (gdy g-metr jest skalibrowany), inaczej z prędkości GPS.
 *
 * Dodatkowo: prędkość maksymalna sesji i własne odcinki pomiarowe (start/meta).
 */
object Performance {

    enum class Phase { WAIT_STOP, READY, RUNNING, DONE, ABORTED }

    /** Punkt wykresu: czas od startu [s], prędkość [km/h], przyspieszenie [g]. */
    data class Sample(val t: Double, val v: Double, val g: Double)

    data class Report(
        val splits: List<Pair<Int, Double>>,   // np. 50 km/h → 3,9 s
        val distanceM: Double,
        val maxG: Double,
        val avgG: Double,
        val samples: List<Sample>,
        val prevBest: Double?,                 // najlepszy wynik przed tym przejazdem
        val gFromSensor: Boolean,
    )

    data class Run(val type: String, val seconds: Double, val timeMs: Long, val note: String? = null, val report: Report? = null)
    data class Segment(val id: Long, val name: String, val sLat: Double, val sLon: Double, val eLat: Double, val eLon: Double)
    data class Pt(val lat: Double, val lon: Double)

    /** Trwający (albo właśnie zakończony) pomiar przyspieszenia. */
    data class Measure(
        val type: String, val from: Int, val to: Int,
        val phase: Phase,
        val message: String,
        val elapsed: Double = 0.0,            // s od startu
        val updatedAtMs: Long = System.currentTimeMillis(),
        val speed: Double = 0.0,
        val g: Double = 0.0,
        val samples: List<Sample> = emptyList(),
        val result: Run? = null,              // gotowy raport (DONE)
    )

    data class State(
        val speed: Double = 0.0,
        val vmax: Double = 0.0,
        val measure: Measure? = null,
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
    private var gpsG = 0.0
    @Volatile private var sensorG = 0.0
    @Volatile private var sensorGAt = 0L
    private var runStart = 0.0               // czas startu (s od epoki)
    private var runSensor = true
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
                    Run(r.getString("type"), r.getDouble("s"), r.getLong("t"), r.optString("note").ifEmpty { null },
                        r.optJSONObject("rep")?.let { runCatching { reportFromJson(it) }.getOrNull() })
                },
                segments = List(segs.length()) { i ->
                    val s = segs.getJSONObject(i)
                    Segment(s.getLong("id"), s.getString("name"), s.getDouble("sLat"), s.getDouble("sLon"), s.getDouble("eLat"), s.getDouble("eLon"))
                },
            )
        }
    }

    private fun reportToJson(r: Report) = JSONObject().apply {
        put("splits", JSONArray().apply { r.splits.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        put("dist", r.distanceM); put("maxG", r.maxG); put("avgG", r.avgG); put("sensor", r.gFromSensor)
        r.prevBest?.let { put("prev", it) }
        put("smp", JSONArray().apply {
            r.samples.forEach { put(JSONArray().put(Math.round(it.t * 100) / 100.0).put(Math.round(it.v * 10) / 10.0).put(Math.round(it.g * 100) / 100.0)) }
        })
    }

    private fun reportFromJson(o: JSONObject): Report {
        val sp = o.getJSONArray("splits"); val sm = o.getJSONArray("smp")
        return Report(
            List(sp.length()) { i -> sp.getJSONArray(i).let { it.getInt(0) to it.getDouble(1) } },
            o.getDouble("dist"), o.getDouble("maxG"), o.getDouble("avgG"),
            List(sm.length()) { i -> sm.getJSONArray(i).let { Sample(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } },
            if (o.has("prev")) o.getDouble("prev") else null,
            o.optBoolean("sensor"),
        )
    }

    private fun persist() {
        val st = _state.value
        val o = JSONObject()
        o.put("runs", JSONArray().apply {
            st.runs.forEach { r ->
                put(JSONObject().put("type", r.type).put("s", r.seconds).put("t", r.timeMs).put("note", r.note ?: "").apply {
                    r.report?.let { put("rep", reportToJson(it)) }
                })
            }
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

    fun best(type: String): Run? = _state.value.runs.filter { it.type == type }.minByOrNull { it.seconds }

    /** Czas (s od epoki) przecięcia prędkości [target] między dwoma odczytami. */
    private fun cross(t0: Double, v0: Double, t1: Double, v1: Double, target: Double): Double =
        if (v1 == v0) t1 else t0 + (t1 - t0) * ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)

    /** Przyspieszenie wzdłużne z czujników (ekran Pomiary podaje je na bieżąco, gdy g-metr jest skalibrowany). */
    fun setSensorG(gLong: Double) { sensorG = gLong; sensorGAt = System.currentTimeMillis() }

    private fun currentG(): Pair<Double, Boolean> =
        if (System.currentTimeMillis() - sensorGAt < 1500) sensorG to true else gpsG to false

    // ---------- pomiar przyspieszenia ----------

    /** START: przygotowuje pomiar od [from] do [to] km/h. */
    @Synchronized
    fun arm(from: Int, to: Int) {
        val v = _state.value.speed
        val type = "$from–$to"
        val m = if (from == 0) {
            if (v < 2) Measure(type, from, to, Phase.READY, "Gotowy – ruszaj! Stoper wystartuje sam.", speed = v)
            else Measure(type, from, to, Phase.WAIT_STOP, "Zatrzymaj auto, żeby zacząć.", speed = v)
        } else {
            if (v < from - 3) Measure(type, from, to, Phase.READY, "Gotowy – przyspiesz. Stoper ruszy przy $from km/h.", speed = v)
            else Measure(type, from, to, Phase.WAIT_STOP, "Zwolnij poniżej ${from - 3} km/h, potem przyspiesz.", speed = v)
        }
        _state.value = _state.value.copy(measure = m)
    }

    /** Przerywa pomiar albo zamyka raport. */
    @Synchronized
    fun closeMeasure() { _state.value = _state.value.copy(measure = null) }

    /** Otwiera raport zapisanego przejazdu. */
    @Synchronized
    fun showReport(r: Run) {
        val (from, to) = r.type.split("–").mapNotNull { it.trim().toIntOrNull() }.let { (it.getOrNull(0) ?: 0) to (it.getOrNull(1) ?: 100) }
        _state.value = _state.value.copy(
            measure = Measure(r.type, from, to, Phase.DONE, "Wynik", elapsed = r.seconds, samples = r.report?.samples ?: emptyList(), result = r)
        )
    }

    private fun measureStep(m: Measure, t: Double, pt: Double, pv: Double, v: Double, first: Boolean, timeMs: Long): Measure {
        val (g, fromSensor) = currentG()
        val now = System.currentTimeMillis()
        return when (m.phase) {
            Phase.DONE, Phase.ABORTED -> m
            Phase.WAIT_STOP -> {
                val ready = if (m.from == 0) v < 2 else v < m.from - 3
                if (ready) m.copy(phase = Phase.READY, speed = v, g = g, updatedAtMs = now,
                    message = if (m.from == 0) "Gotowy – ruszaj! Stoper wystartuje sam." else "Gotowy – przyspiesz. Stoper ruszy przy ${m.from} km/h.")
                else m.copy(speed = v, g = g, updatedAtMs = now)
            }
            Phase.READY -> {
                val startAt = if (m.from == 0) 2.0 else m.from.toDouble()
                val stillOk = if (m.from == 0) true else v < m.from + 2 || pv < m.from
                when {
                    !first && pv < startAt && v >= startAt -> {
                        // 0–100: czas liczony od chwili ruszenia (~1 km/h), wykrycie przy 2 km/h
                        runStart = cross(pt, pv, t, v, if (m.from == 0) 1.0 else startAt)
                        runSensor = fromSensor
                        val el = t - runStart
                        m.copy(phase = Phase.RUNNING, message = "Mierzę…", elapsed = el, speed = v, g = g, updatedAtMs = now,
                            samples = listOf(Sample(0.0, if (m.from == 0) 0.0 else m.from.toDouble(), g), Sample(el, v, g)))
                    }
                    m.from > 0 && v >= m.from && !stillOk ->
                        m.copy(phase = Phase.WAIT_STOP, message = "Zwolnij poniżej ${m.from - 3} km/h, potem przyspiesz.", speed = v, g = g, updatedAtMs = now)
                    m.from == 0 && v >= 2 && pv >= 2 ->
                        m.copy(phase = Phase.WAIT_STOP, message = "Zatrzymaj auto, żeby zacząć.", speed = v, g = g, updatedAtMs = now)
                    else -> m.copy(speed = v, g = g, updatedAtMs = now)
                }
            }
            Phase.RUNNING -> {
                val el = t - runStart
                runSensor = runSensor && fromSensor
                val samples = m.samples + Sample(el, v, g)
                val limit = if (m.from == 0) 60.0 else 90.0
                when {
                    first -> abort(m, "Przerwa w sygnale GPS – spróbuj ponownie.")
                    v < pv - 4 || (m.from > 0 && v < m.from - 5) -> abort(m, "Przerwano – auto zwolniło.")
                    el > limit -> abort(m, "Przerwano – za długo (ponad ${limit.toInt()} s).")
                    v >= m.to -> finish(m, samples, cross(pt, pv, t, v, m.to.toDouble()) - runStart, timeMs)
                    else -> m.copy(elapsed = el, speed = v, g = g, samples = samples, updatedAtMs = now)
                }
            }
        }
    }

    private fun abort(m: Measure, why: String) =
        m.copy(phase = Phase.ABORTED, message = why, updatedAtMs = System.currentTimeMillis())

    private fun finish(m: Measure, raw: List<Sample>, sec: Double, timeMs: Long): Measure {
        // ostatni punkt ucinamy dokładnie na mecie
        val samples = raw.dropLast(1) + Sample(sec, m.to.toDouble(), raw.last().g)
        val splitTargets = if (m.from == 0) listOf(30, 50, 60, 80) else listOf(120, 130, 140)
        val splits = splitTargets.mapNotNull { target ->
            samples.zipWithNext().firstOrNull { (a, b) -> a.v < target && b.v >= target }
                ?.let { (a, b) -> target to cross(a.t, a.v, b.t, b.v, target.toDouble()) }
        }
        var dist = 0.0
        samples.zipWithNext { a, b -> dist += (a.v + b.v) / 2 / 3.6 * (b.t - a.t) }
        val avgG = (m.to - m.from) / 3.6 / sec / 9.81
        val maxG = samples.maxOf { it.g }.coerceAtLeast(avgG)
        val prevBest = best(m.type)?.seconds
        val run = Run(m.type, sec, timeMs, "%.0f m".format(dist),
            Report(splits, dist, maxG, avgG, samples, prevBest, runSensor))
        if (sec in 1.0..90.0) addRun(run)
        return m.copy(phase = Phase.DONE, message = "Meta!", elapsed = sec, speed = m.to.toDouble(), samples = samples,
            result = run, updatedAtMs = System.currentTimeMillis())
    }

    /** Wywoływane przez rejestrator dla każdego odczytu GPS. */
    @Synchronized
    fun onFix(timeMs: Long, speedKmh: Double, lat: Double, lon: Double) {
        val t = timeMs / 1000.0
        val pt = prevT / 1000.0
        val pv = prevV
        val first = prevT == 0L || timeMs - prevT > 5000
        prevT = timeMs; prevV = speedKmh
        if (!first && t - pt in 0.2..3.0) {
            val a = (speedKmh - pv) / 3.6 / (t - pt) / 9.81
            gpsG = gpsG * 0.4 + a * 0.6
        } else gpsG = 0.0

        var st = _state.value.copy(speed = speedKmh, vmax = maxOf(_state.value.vmax, speedKmh))
        st.measure?.let { m -> st = st.copy(measure = measureStep(m, t, pt, pv, speedKmh, first, timeMs), runs = _state.value.runs) }

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
        segActive = null; candidate = null
        _state.value = _state.value.copy(vmax = 0.0, measure = null, segmentName = null, segmentElapsed = null)
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
