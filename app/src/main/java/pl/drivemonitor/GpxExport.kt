package pl.drivemonitor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Pobieranie i udostępnianie plików GPX – zawsze jako poprawny, domknięty plik. */
object GpxExport {

    /**
     * Plik gotowy do wysłania. Trwające nagrywanie eksportujemy jako domkniętą kopię
     * (oryginał dalej się zapisuje), przerwaną trasę domykamy na miejscu.
     */
    private fun exportable(ctx: Context, gpx: File): File {
        if (TripRepository.isRecording(gpx)) {
            val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
            return File(dir, gpx.name).apply {
                writeText(GpxWriter.closedText(gpx) ?: error("Pusty plik"))
            }
        }
        if (!GpxWriter.repair(gpx)) error("Plik trasy jest pusty")
        return gpx
    }

    /** Kopiuje GPX do publicznego folderu Pobrane/DriveMonitor. Zwraca ścieżkę. */
    fun saveToDownloads(ctx: Context, gpx: File): String {
        val src = exportable(ctx, gpx)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            // typ ogólny: przy „application/gpx+xml” część telefonów dopisuje własne rozszerzenie
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/DriveMonitor")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Nie udało się utworzyć pliku w Pobranych")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Pobrane/DriveMonitor/${src.name}"
    }

    /** Systemowe okno „Udostępnij” – mail, Drive, Strava, komunikatory itd. */
    fun share(ctx: Context, gpx: File) {
        val src = exportable(ctx, gpx)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", src)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Trasa ${gpx.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Udostępnij GPX"))
    }
}
