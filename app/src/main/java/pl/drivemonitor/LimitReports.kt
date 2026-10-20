package pl.drivemonitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zgłaszanie prawidłowego ograniczenia prędkości.
 *  1. Poprawka trafia od razu do lokalnej bazy (dla całej drogi OSM) – znak w aplikacji zmienia się natychmiast.
 *  2. Opcjonalnie: anonimowa notatka w OpenStreetMap, którą sprawdzą mapujący.
 *     Wysyłka jest w kolejce: bez zasięgu czeka i ponawia się później; wynik (nr notatki albo
 *     powód odrzucenia przez OSM) widać w Ustawieniach → Moje zgłoszenia.
 */
object LimitReports {

    data class Report(
        val id: Long,               // czas zgłoszenia (ms) – zarazem identyfikator
        val lat: Double, val lon: Double,
        val wayId: Long?, val shownLimit: Int?, val correctKmh: Int,
        val status: String,         // local | queued | sent | failed
        val noteId: Long? = null,
        val error: String? = null,
    )

    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports

    /** Komunikat do pokazania (np. wynik wysyłki) – ekran pokazuje go i zeruje. */
    private val _event = MutableStateFlow<String?>(null)
    val event: StateFlow<String?> = _event
    fun consumeEvent() { _event.value = null }

    private var loaded = false
    @Volatile private var sending = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "limit_reports.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        _reports.value = runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Report(
                    o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                    if (o.has("way")) o.getLong("way") else null,
                    if (o.has("shown")) o.getInt("shown") else null,
                    o.getInt("kmh"), o.getString("status"),
                    if (o.has("note")) o.getLong("note") else null,
                    o.optString("error").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(ctx: Context) {
        val arr = JSONArray()
        _reports.value.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("lat", r.lat); put("lon", r.lon); put("kmh", r.correctKmh); put("status", r.status)
                r.wayId?.let { put("way", it) }; r.shownLimit?.let { put("shown", it) }
                r.noteId?.let { put("note", it) }; r.error?.let { put("error", it) }
            })
        }
        runCatching { file(ctx).writeText(arr.toString()) }
    }

    @Synchronized
    private fun replace(ctx: Context, r: Report) {
        _reports.value = _reports.value.map { if (it.id == r.id) r else it }
        save(ctx)
    }

    /** Zapisuje poprawkę. Zwraca opis do pokazania od razu; wynik wysyłki do OSM przyjdzie w [event]. */
    fun submit(ctx: Context, lat: Double, lon: Double, wayId: Long?, shownLimit: Int?, correctKmh: Int, sendToOsm: Boolean): String {
        load(ctx)
        if (lat == 0.0 && lon == 0.0) return "Brak pozycji GPS – spróbuj za chwilę."
        val parts = ArrayList<String>()
        if (wayId != null) {
            OnlineLimitCache.get(ctx).setOverride(wayId, correctKmh)
            parts += "Ustawiono $correctKmh km/h dla tej drogi"
        } else {
            parts += "Nie rozpoznano drogi (brak danych mapy w tym miejscu) – poprawka lokalna niemożliwa"
        }
        val r = Report(System.currentTimeMillis(), lat, lon, wayId, shownLimit, correctKmh, if (sendToOsm) "queued" else "local")
        synchronized(this) { _reports.value = listOf(r) + _reports.value; save(ctx) }
        if (sendToOsm) {
            parts += "wysyłam notatkę do OpenStreetMap…"
            sendQueued(ctx)
        }
        return parts.joinToString(", ")
    }

    /** Wysyła zaległe notatki (np. zgłoszone bez zasięgu). Bezpieczne do wołania wielokrotnie. */
    fun sendQueued(ctx: Context) {
        load(ctx)
        if (sending) return
        sending = true
        val app = ctx.applicationContext
        Thread {
            try {
                _reports.value.filter { it.status == "queued" }.forEach { r ->
                    val res = postNote(r)
                    when {
                        res.noteId != null -> {
                            replace(app, r.copy(status = "sent", noteId = res.noteId, error = null))
                            _event.value = "Notatka wysłana do OpenStreetMap (nr ${res.noteId}) – mapujący sprawdzą limit."
                        }
                        res.networkError -> {
                            // brak sieci – zostaje w kolejce, ponowimy później
                            replace(app, r.copy(error = res.message))
                            _event.value = "Brak połączenia – notatka do OSM poczeka i wyśle się później."
                            return@Thread
                        }
                        else -> {
                            replace(app, r.copy(status = "failed", error = res.message))
                            _event.value = "OpenStreetMap nie przyjął notatki: ${res.message}"
                        }
                    }
                }
            } finally {
                sending = false
            }
        }.start()
    }

    fun retry(ctx: Context, r: Report) {
        replace(ctx, r.copy(status = "queued", error = null))
        sendQueued(ctx)
    }

    /** Usuwa zgłoszenie z listy i cofa lokalną poprawkę tej drogi. */
    fun remove(ctx: Context, r: Report) {
        r.wayId?.let { OnlineLimitCache.get(ctx).removeOverride(it) }
        synchronized(this) { _reports.value = _reports.value.filter { it.id != r.id }; save(ctx) }
    }

    private data class PostResult(val noteId: Long?, val message: String, val networkError: Boolean = false)

    /** Anonimowa notatka OSM (API 0.6, odpowiedź w JSON z numerem notatki). */
    private fun postNote(r: Report): PostResult {
        val text = buildString {
            append("Ograniczenie prędkości na tej drodze wg kierowcy: ${r.correctKmh} km/h")
            r.shownLimit?.let { append(" (mapa podaje: $it km/h)") }
            r.wayId?.let { append(". Droga: https://www.openstreetmap.org/way/$it") }
            append(". Zgłoszone z aplikacji DriveMonitor – proszę o weryfikację ze znakiem (maxspeed).")
        }
        val body = "lat=${r.lat}&lon=${r.lon}&text=" + URLEncoder.encode(text, "UTF-8")
        return try {
            val conn = URL("https://api.openstreetmap.org/api/0.6/notes.json").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "DriveMonitor/1.1 (Android; speed limit reports)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) {
                val id = runCatching { JSONObject(resp).getJSONObject("properties").getLong("id") }.getOrNull()
                PostResult(id, if (id != null) "wysłano" else "wysłano (bez numeru)").let {
                    if (id == null) it.copy(noteId = -1) else it
                }
            } else {
                Log.w("LimitReports", "OSM HTTP $code: $resp")
                PostResult(null, "HTTP $code" + resp.trim().take(120).let { if (it.isNotEmpty()) " – $it" else "" })
            }
        } catch (e: java.io.IOException) {
            PostResult(null, "brak sieci (${e.javaClass.simpleName})", networkError = true)
        } catch (e: Exception) {
            PostResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    val COMMON_LIMITS = listOf(20, 30, 40, 50, 70, 90, 120)
}
