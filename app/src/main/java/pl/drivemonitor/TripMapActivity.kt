package pl.drivemonitor

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Ekran trasy na natywnej mapie MapLibre: trasa w kolorach przekroczeń, auto, kamera 3D
 * (pochylenie, obrót wokół auta), profil wysokości i odtwarzanie przyspieszone.
 * Mapa działa offline dla okolic pobranych w Ustawieniach.
 */
class TripMapActivity : ComponentActivity() {

    private lateinit var gpx: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        gpx = File(intent.getStringExtra(EXTRA_GPX) ?: return finish())
        setContent { DriveTheme { Screen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var menu by remember { mutableStateOf(false) }
        val data by produceState<TripData?>(null) {
            value = withContext(Dispatchers.IO) { runCatching { TripData.load(this@TripMapActivity, gpx) }.getOrNull() }
        }
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
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć") }
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
            Box(Modifier.padding(pad).fillMaxSize()) {
                val d = data
                when {
                    d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    d.pts.size < 2 -> Text("Ta trasa ma za mało punktów, by ją pokazać.", Modifier.align(Alignment.Center).padding(24.dp))
                    else -> TripViewer(d)
                }
            }
        }
    }

    companion object {
        const val EXTRA_GPX = "gpx"
    }
}

// =========================================================================
// Mapa + odtwarzanie
// =========================================================================

private val SPEEDS = listOf(10, 30, 60, 120, 300)
private const val FOLLOW_ZOOM = 15.5
private const val DEFAULT_PITCH = 55.0

@Composable
private fun TripViewer(data: TripData) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var carSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var mapStatus by remember { mutableStateOf<String?>("Ładowanie mapy…") }

    var tNow by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    var factor by remember { mutableIntStateOf(60) }
    var is3D by remember { mutableStateOf(true) }
    // obrót kamery wokół auta ustawiany palcem w trakcie odtwarzania
    var orbitBearing by remember { mutableDoubleStateOf(0.0) }
    var orbitPitch by remember { mutableDoubleStateOf(DEFAULT_PITCH) }
    val camBearing = remember { doubleArrayOf(Double.NaN) }

    // cykl życia MapView
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onDestroy() }
    }

    // styl, trasa, auto, dopasowanie widoku
    LaunchedEffect(Unit) {
        mapView.addOnDidFailLoadingMapListener { msg ->
            mapStatus = "Mapa niedostępna: $msg. Bez internetu pobierz okolicę w Ustawieniach → Mapa offline."
        }
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.setStyle(Style.Builder().fromUri(MapStyle.STYLE_URL)) { style ->
                style.addSource(MapStyle.routeSource(data.routeFeatures()))
                MapStyle.routeLayers().forEach { style.addLayer(it) }
                val src = MapStyle.carSource(data.pts[0].lat, data.pts[0].lon)
                style.addSource(src)
                MapStyle.carLayers().forEach { style.addLayer(it) }
                carSource = src
                mapStatus = null
            }
            data.bounds()?.let { b ->
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 120))
            }
            map = m
        }
    }

    fun applyFrame(follow: Boolean, dt: Double) {
        val s = data.stateAt(tNow)
        carSource?.setGeoJson(Point.fromLngLat(s.lon, s.lat))
        val m = map ?: return
        if (!follow) return
        val ahead = data.pointAhead(s.i, s.dist, 120.0)
        val k = cos(Math.toRadians(s.lat))
        val target = (Math.toDegrees(atan2((ahead.lon - s.lon) * k, ahead.lat - s.lat)) + 360) % 360
        if (camBearing[0].isNaN()) camBearing[0] = target
        val diff = ((target - camBearing[0] + 540) % 360) - 180
        camBearing[0] = (camBearing[0] + diff * (1 - exp(-dt * 1.8)) + 360) % 360
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(s.lat, s.lon))
                    .zoom(m.cameraPosition.zoom.takeIf { it > 11 } ?: FOLLOW_ZOOM)
                    .tilt(if (is3D) orbitPitch else 0.0)
                    .bearing((camBearing[0] + orbitBearing + 360) % 360)
                    .build()
            )
        )
    }

    // pętla odtwarzania – jedna klatka na odświeżenie ekranu
    LaunchedEffect(playing) {
        val m = map
        m?.uiSettings?.setAllGesturesEnabled(!playing)
        if (!playing) return@LaunchedEffect
        if (tNow >= data.totalT) tNow = 0.0
        camBearing[0] = Double.NaN
        m?.moveCamera(CameraUpdateFactory.zoomTo(FOLLOW_ZOOM))
        var last = withFrameNanos { it }
        while (isActive && playing) {
            val now = withFrameNanos { it }
            val dt = min(0.1, (now - last) / 1e9)
            last = now
            tNow = min(data.totalT, tNow + dt * factor)
            applyFrame(follow = true, dt = dt)
            if (tNow >= data.totalT) playing = false
        }
    }
    // przewijanie (bez odtwarzania): przesuwamy tylko auto
    LaunchedEffect(tNow, carSource) { if (!playing) applyFrame(follow = false, dt = 0.0) }

    val state = data.stateAt(tNow)

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // w trakcie odtwarzania: przeciąganie palcem obraca kamerę wokół auta
        if (playing) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        orbitBearing = (orbitBearing - drag.x * 0.3 + 360) % 360
                        if (is3D) orbitPitch = (orbitPitch - drag.y * 0.2).coerceIn(0.0, 60.0)
                    }
                }
            )
        }

        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsCard(data)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    mapStatus?.let { OverlayChip(it) }
                    val orbitChanged = abs(((orbitBearing + 180) % 360) - 180) > 5 || abs(orbitPitch - DEFAULT_PITCH) > 3
                    if (playing && orbitChanged) {
                        FilledTonalButton(onClick = { orbitBearing = 0.0; orbitPitch = DEFAULT_PITCH }) { Text("⟲ Za autem") }
                    }
                }
                Hud(state)
            }
        }

        BottomPanel(
            data = data, state = state, tNow = tNow, playing = playing, factor = factor, is3D = is3D,
            onPlay = { playing = !playing },
            onFactor = { factor = it },
            onToggle3D = {
                is3D = !is3D
                map?.let { m ->
                    if (!playing) m.easeCamera(CameraUpdateFactory.tiltTo(if (is3D) DEFAULT_PITCH else 0.0), 600)
                }
            },
            onSeek = { playing = false; tNow = it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
        )
    }
}

@Composable
private fun overlayColor() = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f)

@Composable
private fun OverlayChip(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = overlayColor(), shadowElevation = 2.dp) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsCard(data: TripData) {
    val pl = Locale("pl")
    val items = buildList {
        add("Dystans" to "%.1f km".format(pl, data.totalD / 1000))
        add("Czas" to formatDuration(data.totalT))
        add("Max" to "%.0f km/h".format(data.maxSpeed))
        add("W limicie" to "%.1f km".format(pl, data.catKm[1]))
        add("Ponad limitem" to "%.1f km".format(pl, data.overKm))
        if (data.catKm[0] >= 0.05) add("Bez limitu" to "%.1f km".format(pl, data.catKm[0]))
        data.stats?.optInt("harshBrakes")?.takeIf { it > 0 }?.let { add("Hamowania" to it.toString()) }
        data.ele?.let { e -> add("↑ / ↓" to "%.0f / %.0f m".format(data.gain, data.loss)); add("Wys." to "%.0f–%.0f m".format(e.min(), e.max())) }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = overlayColor(), shadowElevation = 3.dp) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { (k, v) ->
                Column {
                    Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        v, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = when (k) {
                            "W limicie" -> SpeedColors[1]
                            "Ponad limitem" -> if (data.overKm > 0.05) SpeedColors[4] else MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Hud(s: TripData.State) {
    val over = s.limit != null && s.speed > s.limit
    Surface(shape = RoundedCornerShape(16.dp), color = overlayColor(), shadowElevation = 3.dp) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.End) {
            Text(
                "%.0f".format(s.speed), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = if (over) SpeedColors[4] else MaterialTheme.colorScheme.onSurface
            )
            Text("km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White).border(5.dp, Color(0xFFD32F2F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(s.limit?.toString() ?: "–", color = Color(0xFF111111), fontWeight = FontWeight.Bold)
            }
            s.ele?.let {
                Spacer(Modifier.height(4.dp))
                Text("%.0f m n.p.m.".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomPanel(
    data: TripData, state: TripData.State, tNow: Double, playing: Boolean, factor: Int, is3D: Boolean,
    onPlay: () -> Unit, onFactor: (Int) -> Unit, onToggle3D: () -> Unit, onSeek: (Double) -> Unit,
    modifier: Modifier,
) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale("pl")) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = overlayColor(), shadowElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Profile(data, state.dist, onSeek)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val small = MaterialTheme.typography.labelSmall
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(clock.format(Date(data.startMs + (tNow * 1000).toLong())), style = small, color = muted)
                Text(
                    "%.1f / %.1f km · %s".format(Locale("pl"), state.dist / 1000, data.totalD / 1000, formatDuration(tNow)),
                    style = small, color = muted
                )
                Text(clock.format(Date(data.startMs + (data.totalT * 1000).toLong())), style = small, color = muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(46.dp)) {
                    if (playing) Text("❚❚", fontWeight = FontWeight.Bold) else Icon(Icons.Filled.PlayArrow, contentDescription = "Odtwórz")
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SPEEDS.forEach { v ->
                        val on = v == factor
                        Surface(
                            onClick = { onFactor(v) },
                            shape = RoundedCornerShape(50),
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                "×$v", Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Surface(
                    onClick = onToggle3D, shape = RoundedCornerShape(50),
                    color = if (is3D) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "3D", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold,
                        color = if (is3D) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Profil wysokości (albo prędkości, gdy brak wysokości) z paskiem przekroczeń; dotyk przewija trasę. */
@Composable
private fun Profile(data: TripData, cursorDist: Double, onSeek: (Double) -> Unit) {
    val values = data.ele ?: DoubleArray(data.pts.size) { data.pts[it].speedKmh }
    val vMin = values.min(); val vMax = values.max()
    val line = MaterialTheme.colorScheme.primary
    val cursor = MaterialTheme.colorScheme.onSurface
    val unit = if (data.ele != null) "m" else "km/h"

    fun seekTo(x: Float, width: Int) {
        val dist = (x / width).coerceIn(0f, 1f) * data.totalD
        var i = 0
        while (i < data.d.size - 1 && data.d[i] < dist) i++
        onSeek(data.t[i])
    }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                (if (data.ele != null) "Wysokość " else "Prędkość ") + "%.0f %s".format(vMax, unit),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("min %.0f %s".format(vMin, unit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(
            Modifier.fillMaxWidth().height(78.dp)
                .pointerInput(data) { detectTapGestures { seekTo(it.x, size.width) } }
                .pointerInput(data) { detectHorizontalDragGestures { change, _ -> change.consume(); seekTo(change.position.x, size.width) } }
        ) {
            val w = size.width; val h = size.height
            val strip = 6.dp.toPx()
            val bottom = h - strip - 3.dp.toPx()
            fun x(dist: Double) = (dist / data.totalD * w).toFloat()
            fun y(v: Double) = (bottom - (v - vMin) / max(1.0, vMax - vMin) * (bottom - 2.dp.toPx())).toFloat()
            val step = max(1, values.size / (w.toInt() * 2).coerceAtLeast(1))

            val path = Path().apply {
                moveTo(0f, bottom)
                var i = 0
                while (i < values.size) { lineTo(x(data.d[i]), y(values[i])); i += step }
                lineTo(w, y(values.last())); lineTo(w, bottom); close()
            }
            drawPath(path, Brush.verticalGradient(listOf(line.copy(alpha = 0.45f), line.copy(alpha = 0.05f)), 0f, bottom))
            val stroke = Path().apply {
                moveTo(0f, y(values[0]))
                var i = 0
                while (i < values.size) { lineTo(x(data.d[i]), y(values[i])); i += step }
            }
            drawPath(stroke, line, style = Stroke(1.5.dp.toPx()))
            for (i in 1 until data.pts.size) {
                drawRect(
                    SpeedColors[data.cats[i]],
                    topLeft = Offset(x(data.d[i - 1]), h - strip),
                    size = androidx.compose.ui.geometry.Size(max(1f, x(data.d[i]) - x(data.d[i - 1]) + 0.5f), strip)
                )
            }
            val cx = x(cursorDist)
            drawLine(cursor, Offset(cx, 0f), Offset(cx, h), 1.dp.toPx())
        }
    }
}
