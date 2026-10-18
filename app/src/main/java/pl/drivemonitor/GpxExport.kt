package pl.drivemonitor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Pobieranie i udostępnianie plików GPX. */
object GpxExport {

    /** Kopiuje GPX do publicznego folderu Pobrane/DriveMonitor. Zwraca nazwę pliku. */
    fun saveToDownloads(ctx: Context, gpx: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, gpx.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/DriveMonitor")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Nie udało się utworzyć pliku w Pobranych")
        resolver.openOutputStream(uri)!!.use { out -> gpx.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Pobrane/DriveMonitor/${gpx.name}"
    }

    /** Systemowe okno „Udostępnij” – mail, Drive, Strava, komunikatory itd. */
    fun share(ctx: Context, gpx: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", gpx)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Trasa ${gpx.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Udostępnij GPX"))
    }
}
