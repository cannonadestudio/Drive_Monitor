package pl.drivemonitor

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Instant

/** Czyta GPX zapisany przez GpxWriter (działa też z dowolnym innym GPX – bez limitów). */
object GpxParser {

    fun parse(file: File): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        file.inputStream().use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, "UTF-8")

            var lat = 0.0; var lon = 0.0
            var ele: Double? = null; var time = 0L
            var speed: Double? = null; var limit: Int? = null; var src: String? = null
            var text = ""

            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> if (p.name == "trkpt") {
                        lat = p.getAttributeValue(null, "lat").toDouble()
                        lon = p.getAttributeValue(null, "lon").toDouble()
                        ele = null; time = 0L; speed = null; limit = null; src = null
                    }
                    XmlPullParser.TEXT -> text = p.text.trim()
                    XmlPullParser.END_TAG -> when (p.name) {
                        "ele" -> ele = text.toDoubleOrNull()
                        "time" -> time = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
                        "dm:speed" -> speed = text.toDoubleOrNull()
                        "dm:limit" -> limit = text.toIntOrNull()
                        "dm:limitSrc" -> src = text
                        "trkpt" -> out += TrackPoint(time, lat, lon, ele, speed ?: Double.NaN, 0f, limit, src)
                    }
                }
            }
        }
        return fillMissingSpeeds(out)
    }

    /** Dla obcych plików GPX bez prędkości – liczymy ją z położenia i czasu. */
    private fun fillMissingSpeeds(pts: List<TrackPoint>): List<TrackPoint> =
        pts.mapIndexed { i, p ->
            if (!p.speedKmh.isNaN()) p
            else if (i == 0) p.copy(speedKmh = 0.0)
            else {
                val q = pts[i - 1]
                val dt = (p.timeMs - q.timeMs) / 1000.0
                val d = SpeedLimitProvider.distanceToSegmentM(p.lat, p.lon, q.lat, q.lon, q.lat, q.lon)
                p.copy(speedKmh = if (dt > 0) d / dt * 3.6 else 0.0)
            }
        }

    /** Kompaktowy JSON dla mapy: [lon, lat, ele, speed, limit, sekundy od startu]. */
    fun toMapJson(name: String, pts: List<TrackPoint>, stats: JSONObject?): String {
        val t0 = pts.firstOrNull()?.timeMs ?: 0L
        val arr = JSONArray()
        pts.forEach { p ->
            arr.put(JSONArray().apply {
                put(p.lon); put(p.lat)
                put(p.altitude ?: JSONObject.NULL)
                put(Math.round(p.speedKmh * 10) / 10.0)
                put(p.limitKmh ?: JSONObject.NULL)
                put((p.timeMs - t0) / 1000.0)
            })
        }
        return JSONObject().apply {
            put("name", name)
            put("startMs", t0)
            put("points", arr)
            put("stats", stats ?: JSONObject.NULL)
        }.toString()
    }
}
