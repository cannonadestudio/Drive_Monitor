package pl.drivemonitor

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

/**
 * Mapa trasy: kolorowanie przekroczeń, profil wysokości i animacja 3D.
 * Rysuje MapLibre GL JS w WebView (assets/trip_map.html) – daje teren 3D,
 * którego natywne biblioteki map na Androida jeszcze dobrze nie obsługują.
 */
class TripMapActivity : ComponentActivity() {

    private lateinit var gpx: File
    private lateinit var tripJson: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gpx = File(intent.getStringExtra(EXTRA_GPX) ?: return finish())
        val statsFile = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")
        val stats = if (statsFile.exists()) JSONObject(statsFile.readText()) else null
        tripJson = GpxParser.toMapJson(gpx.nameWithoutExtension, GpxParser.parse(gpx), stats)

        setContent { MaterialTheme { Screen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(gpx.nameWithoutExtension.replace('_', ' ')) },
                    actions = {
                        TextButton(onClick = {
                            val msg = runCatching { "Zapisano: " + GpxExport.saveToDownloads(this@TripMapActivity, gpx) }
                                .getOrElse { "Błąd zapisu: ${it.message}" }
                            Toast.makeText(this@TripMapActivity, msg, Toast.LENGTH_LONG).show()
                        }) { Text("Pobierz GPX") }
                        TextButton(onClick = { GpxExport.share(this@TripMapActivity, gpx) }) {
                            Text("Udostępnij")
                        }
                    }
                )
            }
        ) { pad ->
            AndroidView(
                factory = { ctx -> createWebView(ctx) },
                modifier = Modifier.padding(pad).fillMaxSize()
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: android.content.Context): WebView {
        // Strona serwowana z https://appassets.androidplatform.net – prawdziwy origin,
        // więc kafelki mapy i terenu ładują się bez problemów z CORS.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .build()

        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/trip_map.html")
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getTrip(): String = tripJson
    }

    companion object {
        const val EXTRA_GPX = "gpx"
    }
}
