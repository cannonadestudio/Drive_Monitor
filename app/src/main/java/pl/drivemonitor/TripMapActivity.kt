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

    /** Liczony leniwie na wątku WebView – odczyt GPX i dopasowanie do dróg nie blokują ekranu. */
    private val tripJson: String by lazy { MapWeb.tripJson(this, gpx) }

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

    private fun createWebView(ctx: android.content.Context): WebView =
        MapWeb.create(ctx, preview = false) { tripJson }.apply {
            addJavascriptInterface(Bridge(), "Android")   // zapasowa droga przekazania danych
        }

    inner class Bridge {   // zapasowa droga przekazania danych
        @JavascriptInterface
        fun getTrip(): String = tripJson
    }

    companion object {
        const val EXTRA_GPX = "gpx"
    }
}
