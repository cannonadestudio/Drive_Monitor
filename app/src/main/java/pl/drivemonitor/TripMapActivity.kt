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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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

    /**
     * JSON trasy dla strony mapy. Liczony leniwie – pierwsze żądanie przychodzi z wątku
     * WebView, więc odczyt GPX i dopasowanie do dróg nie blokują interfejsu.
     */
    private val tripJson: String by lazy {
        runCatching {
            val statsFile = File(gpx.parentFile, gpx.nameWithoutExtension + ".json")
            val stats = if (statsFile.exists()) runCatching { JSONObject(statsFile.readText()) }.getOrNull() else null
            stats?.remove("thumb"); stats?.remove("thumb2")
            val points = DisplayTrack.prepare(this@TripMapActivity, GpxParser.parse(gpx))
            GpxParser.toMapJson(gpx.nameWithoutExtension, points, stats)
        }.getOrElse { """{"points":[],"error":"${it.message?.replace("\"", "'")}"}""" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gpx = File(intent.getStringExtra(EXTRA_GPX) ?: return finish())
        setContent { DriveTheme { Screen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var menu by remember { mutableStateOf(false) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            TripRepository.formatTitle(gpx.nameWithoutExtension),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć")
                        }
                    },
                    actions = {
                        IconButton(onClick = { GpxExport.share(this@TripMapActivity, gpx) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Udostępnij GPX")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Więcej") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Zapisz GPX w Pobranych") },
                                    onClick = {
                                        menu = false
                                        val msg = runCatching { "Zapisano: " + GpxExport.saveToDownloads(this@TripMapActivity, gpx) }
                                            .getOrElse { "Błąd zapisu: ${it.message}" }
                                        Toast.makeText(this@TripMapActivity, msg, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
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
            // dane trasy jako zwykły plik JSON pod tym samym adresem co strona
            .addPathHandler("/data/", WebViewAssetLoader.PathHandler { path ->
                if (path == "trip.json")
                    android.webkit.WebResourceResponse("application/json", "utf-8", tripJson.byteInputStream())
                else null
            })
            .build()

        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(Bridge(), "Android")
            // błędy strony mapy trafiają do logcat (tag TripMap)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("TripMap", "${m.messageLevel()}: ${m.message()} (${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/trip_map.html")
        }
    }

    inner class Bridge {   // zapasowa droga przekazania danych
        @JavascriptInterface
        fun getTrip(): String = tripJson
    }

    companion object {
        const val EXTRA_GPX = "gpx"
    }
}
