package pl.drivemonitor

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Zapis GPX 1.1 punkt po punkcie. Każdy punkt to jedna pełna linia zapisywana
 * i opróżniana na dysk od razu, więc nagłe zabicie aplikacji gubi najwyżej
 * ostatni punkt, a plik da się zawsze domknąć funkcją [closedText].
 * W rozszerzeniach zapisujemy prędkość (km/h) i obowiązujące ograniczenie.
 */
class GpxWriter(val file: File) {
    private val stream = FileOutputStream(file)
    private val out = OutputStreamWriter(stream, Charsets.UTF_8)

    init {
        out.write(header(file.nameWithoutExtension))
        out.flush()
    }

    fun add(p: TrackPoint) {
        val sb = StringBuilder(220)
        sb.append("<trkpt lat=\"").append("%.7f".format(Locale.US, p.lat))
            .append("\" lon=\"").append("%.7f".format(Locale.US, p.lon)).append("\">")
        p.altitude?.let { sb.append("<ele>").append("%.1f".format(Locale.US, it)).append("</ele>") }
        sb.append("<time>").append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(p.timeMs)))
            .append("</time><extensions>")
            .append("<dm:speed>").append("%.1f".format(Locale.US, p.speedKmh)).append("</dm:speed>")
        p.limitKmh?.let { sb.append("<dm:limit>").append(it).append("</dm:limit>") }
        p.limitSource?.let { sb.append("<dm:limitSrc>").append(it).append("</dm:limitSrc>") }
        sb.append("</extensions></trkpt>\n")
        out.write(sb.toString())
        out.flush()
    }

    fun close() {
        out.write(FOOTER)
        out.flush()
        stream.fd.sync()
        out.close()
    }

    companion object {
        const val FOOTER = "</trkseg></trk>\n</gpx>\n"

        fun header(name: String) = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="DriveMonitor" xmlns="http://www.topografix.com/GPX/1/1" xmlns:dm="https://drivemonitor.local/gpx/1">
<trk><name>$name</name><trkseg>
"""

        /**
         * Treść pliku jako poprawny GPX. Odcina wszystko za ostatnim kompletnym punktem
         * (urwana linia po przerwaniu zapisu, także w plikach „domkniętych” przez v0.1–0.2
         * zaraz za uciętym punktem) i dopisuje zamknięcie. Dla poprawnego pliku nic nie zmienia.
         * Zwraca null, gdy plik nie ma nawet nagłówka.
         */
        fun closedText(f: File): String? {
            var core = f.readText().trimEnd()
            for (suffix in listOf("</gpx>", "</trk>", "</trkseg>")) core = core.removeSuffix(suffix).trimEnd()
            val lastPoint = core.lastIndexOf("</trkpt>")
            val segStart = core.indexOf("<trkseg>")
            val body = when {
                lastPoint >= 0 && lastPoint > segStart -> core.substring(0, lastPoint + "</trkpt>".length)
                segStart >= 0 -> core.substring(0, segStart + "<trkseg>".length)
                else -> return null
            }
            return body + "\n" + FOOTER
        }

        /** Naprawia plik na miejscu, jeśli trzeba. false = plik bezużyteczny (brak nagłówka). */
        fun repair(f: File): Boolean {
            val fixed = closedText(f) ?: return false
            if (fixed != f.readText()) f.writeText(fixed)
            return true
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
