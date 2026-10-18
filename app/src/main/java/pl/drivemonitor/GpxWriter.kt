package pl.drivemonitor

import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Strumieniowy zapis GPX 1.1 – każdy punkt trafia od razu na dysk,
 * więc nagła śmierć procesu nie kasuje całej trasy (brakuje wtedy tylko zamknięcia tagów,
 * które TripRepository dopisuje przy następnym starcie).
 * W rozszerzeniach zapisujemy prędkość (km/h) i obowiązujące ograniczenie.
 */
class GpxWriter(val file: File) {
    private val out: BufferedWriter = file.bufferedWriter()
    private var points = 0

    init {
        out.write(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="DriveMonitor"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:dm="https://drivemonitor.local/gpx/1">
<trk><name>${file.nameWithoutExtension}</name><trkseg>
"""
        )
    }

    fun add(p: TrackPoint) {
        val time = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(p.timeMs))
        out.write(
            "<trkpt lat=\"%.7f\" lon=\"%.7f\">".format(java.util.Locale.US, p.lat, p.lon)
        )
        p.altitude?.let { out.write("<ele>%.1f</ele>".format(java.util.Locale.US, it)) }
        out.write("<time>$time</time><extensions>")
        out.write("<dm:speed>%.1f</dm:speed>".format(java.util.Locale.US, p.speedKmh))
        p.limitKmh?.let { out.write("<dm:limit>$it</dm:limit>") }
        p.limitSource?.let { out.write("<dm:limitSrc>$it</dm:limitSrc>") }
        out.write("</extensions></trkpt>\n")
        if (++points % 20 == 0) out.flush()
    }

    fun close() {
        out.write("</trkseg></trk>\n</gpx>\n")
        out.close()
    }

    companion object {
        /** Domyka plik GPX, który nie został zamknięty (np. proces zabity przez system). */
        fun repairIfOpen(f: File) {
            // świeży plik może być właśnie zapisywany przez trwającą trasę – nie ruszamy
            if (System.currentTimeMillis() - f.lastModified() < 5 * 60_000) return
            val tail = f.readText().takeLast(64)
            if (!tail.contains("</gpx>")) f.appendText("</trkseg></trk>\n</gpx>\n")
        }
    }
}

data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val speedKmh: Double,
    val accuracyM: Float,
    val limitKmh: Int?,
    val limitSource: String?,
)
