package pl.drivemonitor

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pamięć mapy w telefonie. Wszystkie zapytania strony mapy o styl, kafelki, czcionki,
 * ikony i teren przechodzą przez aplikację:
 *  - kafelki / czcionki / ikony: najpierw z dysku, a gdy ich brak – z sieci (i zapis na dysk),
 *  - style i opisy źródeł (JSON): najpierw z sieci (świeże), a bez sieci – z dysku.
 * Dzięki temu raz obejrzana lub pobrana okolica działa bez internetu.
 */
class MapTileCache private constructor(ctx: Context) {

    val dir = File(ctx.filesDir, "mapcache").apply { mkdirs() }

    fun isMapUrl(url: Uri): Boolean =
        url.host == "tiles.openfreemap.org" ||
            (url.host == "s3.amazonaws.com" && url.path?.startsWith("/elevation-tiles-prod/") == true)

    private fun fileFor(url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dir, h.substring(0, 2)).apply { mkdirs() }, h)
    }

    private fun isJson(url: Uri): Boolean {
        val p = url.path ?: return true
        return p.endsWith(".json") || !p.substringAfterLast('/').contains('.')   // /styles/liberty, /planet
    }

    private fun mime(url: Uri): String {
        val p = url.path ?: ""
        return when {
            p.endsWith(".pbf") || p.endsWith(".mvt") -> "application/x-protobuf"
            p.endsWith(".png") -> "image/png"
            p.endsWith(".webp") -> "image/webp"
            p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/json"
        }
    }

    /** Zwraca zawartość z pamięci albo z sieci (zapisując ją). null = brak sieci i brak kopii. */
    fun fetch(url: String, preferNetwork: Boolean = isJson(Uri.parse(url))): ByteArray? {
        val f = fileFor(url)
        if (!preferNetwork && f.exists() && f.length() > 0) return f.readBytes()
        return try {
            val bytes = download(url)
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            bytes
        } catch (e: Exception) {
            if (f.exists()) f.readBytes() else null
        }
    }

    fun has(url: String) = fileFor(url).let { it.exists() && it.length() > 0 }

    /** Wywoływane przez WebView dla każdego zapytania strony mapy. */
    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        if (req.method != "GET" || !isMapUrl(req.url)) return null
        val url = req.url.toString()
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
        val bytes = fetch(url)
        return if (bytes != null) {
            WebResourceResponse(mime(req.url), null, 200, "OK", headers, ByteArrayInputStream(bytes))
        } else {
            // brak sieci i brak kopii: pusta odpowiedź 204 – MapLibre po prostu nie narysuje tego kafelka
            WebResourceResponse(mime(req.url), null, 204, "No Content", headers, ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "DriveMonitor/0.7 (Android)")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        Log.i("MapTileCache", "Wyczyszczono pamięć mapy")
    }

    companion object {
        @Volatile private var instance: MapTileCache? = null
        fun get(ctx: Context): MapTileCache =
            instance ?: synchronized(this) { instance ?: MapTileCache(ctx.applicationContext).also { instance = it } }
    }
}
