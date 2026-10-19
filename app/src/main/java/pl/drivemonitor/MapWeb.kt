package pl.drivemonitor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

/**
 * WebView ze stroną mapy (assets/trip_map.html):
 *  - strona, biblioteka mapy (wbudowana w APK) i dane trasy serwowane lokalnie,
 *  - styl, kafelki i teren przez [MapTileCache] (działa offline dla zapisanych okolic).
 * preview = mały, nieinteraktywny podgląd trasy na mapie (ekran główny).
 */
object MapWeb {

    /** JSON trasy dla strony: punkty bez postojów, dopasowane do dróg, plus statystyki. */
    fun tripJson(ctx: Context, gpx: File): String = runCatching {
        val statsFile = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")
        val stats = if (statsFile.exists()) runCatching { JSONObject(statsFile.readText()) }.getOrNull() else null
        stats?.remove("thumb"); stats?.remove("thumb2")
        val points = DisplayTrack.prepare(ctx, GpxParser.parse(gpx))
        GpxParser.toMapJson(gpx.nameWithoutExtension, points, stats)
    }.getOrElse { JSONObject().put("points", org.json.JSONArray()).put("error", it.message ?: "błąd").toString() }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, preview: Boolean, tripJson: () -> String): WebView {
        val cache = MapTileCache.get(ctx)
        val json by lazy(tripJson)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .addPathHandler("/data/", WebViewAssetLoader.PathHandler { path ->
                if (path == "trip.json") WebResourceResponse("application/json", "utf-8", json.byteInputStream()) else null
            })
            .build()

        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.i("TripMap", "${m.messageLevel()}: ${m.message()} (${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url) ?: cache.intercept(request)
            }
            if (preview) {
                // podgląd tylko do oglądania – dotyk obsługuje karta nad nim
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setOnTouchListener { _, _ -> true }
            }
            loadUrl("https://appassets.androidplatform.net/assets/trip_map.html" + if (preview) "?preview=1" else "")
        }
    }
}
