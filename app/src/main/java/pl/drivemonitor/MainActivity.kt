package pl.drivemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private enum class Page { HOME, SETTINGS, STATS, PERF }

    private var page by mutableStateOf(Page.HOME)
    private var refresh by mutableIntStateOf(0)
    private var showCarPicker by mutableStateOf(false)
    private var tripToDelete by mutableStateOf<TripRepository.Trip?>(null)
    private var confirmDeleteAll by mutableStateOf(false)

    // ---------- uprawnienia i ustawienia systemowe ----------

    private val basePerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    // lokalizacja w tle – Android wymaga osobnego pytania, po lokalizacji podstawowej
    private val bgPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    // opcjonalna baza offline: plik .sqlite kopiowany do folderu aplikacji
    private val importDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val target = SpeedLimitProvider.offlineFile(this)
        val tmp = File(target.parentFile, "import.tmp")
        val msg = runCatching {
            contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            val n = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM segments", null).use { it.moveToFirst(); it.getLong(0) }
            }
            tmp.renameTo(target)
            "Zaimportowano bazę: $n odcinków dróg"
        }.getOrElse { tmp.delete(); "To nie jest baza limitów DriveMonitor (${it.message})" }
        toast(msg)
        refresh++
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun baseOk() = has(Manifest.permission.ACCESS_FINE_LOCATION) && has(Manifest.permission.BLUETOOTH_CONNECT)
    private fun bgOk() = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun carName() = Prefs.carName(this) ?: Prefs.carMac(this)
    private fun batteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun setupDoneCount() = listOf(baseOk(), bgOk(), carName() != null, batteryExempt()).count { it }

    private fun requestBasePerms() = basePerms.launch(arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    ))

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    )

    /** Sparowane urządzenia Bluetooth (nazwa, MAC) – zestaw auta jest wśród nich. */
    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<Pair<String, String>> = try {
        getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .map { (it.name ?: it.address) to it.address }
            .sortedBy { it.first.lowercase() }
    } catch (e: SecurityException) { emptyList() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun openTrip(trip: TripRepository.Trip) = startActivity(
        Intent(this, TripMapActivity::class.java).putExtra(TripMapActivity.EXTRA_GPX, trip.gpx.absolutePath)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.maplibre.android.MapLibre.getInstance(this)
        LimitReports.sendQueued(this)   // notatki zgłoszone bez zasięgu
        enableEdgeToEdge()
        setContent {
            DriveTheme {
                BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
                when (page) {
                    Page.HOME -> HomeScreen()
                    Page.SETTINGS -> SettingsScreen()
                    Page.STATS -> StatsScreen()
                    Page.PERF -> PerfScreen()
                }
                val reportEvent by LimitReports.event.collectAsState()
                LaunchedEffect(reportEvent) {
                    reportEvent?.let { toast(it); LimitReports.consumeEvent() }
                }
                if (showCarPicker) CarPickerDialog()
                tripToDelete?.let { DeleteTripDialog(it) }
                if (confirmDeleteAll) DeleteAllDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++   // np. po powrocie z ustawień systemu albo z ekranu trasy
    }

    // =====================================================================
    // Ekran główny
    // =====================================================================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen() {
        val r = refresh
        // stan nagrywania obserwowany na żywo – ekran sam zauważa start i koniec jazdy
        val live by LiveTrip.state.collectAsState()
        val recording = live != null || TripRecorderService.currentFile != null
        val trips = remember(r, recording) { TripRepository.list(this) }
        val setupDone = remember(r) { setupDoneCount() }

        // w trakcie nagrywania odświeżamy listę (miniatura trwającej trasy) co 15 s
        LaunchedEffect(recording) {
            refresh++
            while (recording) { delay(15_000); refresh++ }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Drive Monitor", fontWeight = FontWeight.Bold)
                            Text(
                                tripsLabel(trips.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { page = Page.PERF }) {
                            Text("⏱", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = { page = Page.STATS }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Statystyki")
                        }
                        IconButton(onClick = { page = Page.SETTINGS }) {
                            BadgedBox(badge = { if (setupDone < 4) Badge() }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Ustawienia")
                            }
                        }
                    }
                )
            }
        ) { pad ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { StatusCard(recording, setupDone) }

                val latest = trips.firstOrNull()
                // po jeździe: podsumowanie ostatniej trasy (w trakcie jazdy liczby są w karcie na żywo)
                if (!recording && latest?.stats != null) {
                    item { SectionTitle("Ostatnia jazda") }
                    item { LastTripTiles(latest.stats) }
                }
                if (latest != null) {
                    item { SectionTitle(if (latest.recording) "Trwająca trasa" else "Mapa ostatniej trasy") }
                    item(key = "latest-" + latest.gpx.name) { LatestTripCard(latest, r) }
                }
                if (trips.size > 1) {
                    item { SectionTitle("Wcześniejsze trasy") }
                    items(trips.drop(1), key = { it.gpx.name }) { TripRow(it, r) }
                }
                if (trips.isEmpty()) item { EmptyState() }
            }
        }
    }

    /** Liczby ostatniej zakończonej jazdy. */
    @Composable
    private fun LastTripTiles(js: JSONObject) {
        val pl = Locale("pl")
        val cats = TripStats.kmByCategory(js)
        val km = js.optDouble("distanceKm", 0.0)
        val overKm = cats[2] + cats[3] + cats[4] + cats[5]
        val seconds = js.optDouble("durationS", js.optDouble("durationMin", 0.0) * 60)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Dystans", "%.1f km".format(pl, km), Modifier.weight(1f))
                StatTile("Czas", formatDuration(seconds), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("W limicie", "%.1f km".format(pl, cats[1]), Modifier.weight(1f), accent = SpeedColors[1], note = pctOf(cats[1], km))
                StatTile("Ponad limitem", "%.1f km".format(pl, overKm), Modifier.weight(1f),
                    accent = if (overKm > 0.05) SpeedColors[4] else SpeedColors[1], note = pctOf(overKm, km))
            }
            DistanceBar(cats)
        }
    }

    private fun tripsLabel(n: Int) = when {
        n == 1 -> "1 trasa"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "$n trasy"
        else -> "$n tras"
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
        )
    }

    /** Duża granatowa karta: co aplikacja teraz robi, a w trakcie jazdy – podgląd na żywo. */
    @Composable
    private fun StatusCard(recording: Boolean, setupDone: Int) {
        val live by LiveTrip.state.collectAsState()
        val car = carName()
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom)))
                .padding(20.dp)
        ) {
            val l = live
            if (recording && l != null) LivePanel(l)
            else {
                val (title, subtitle) = when {
                    recording -> "Nagrywanie trasy" to "Czekam na sygnał GPS…"
                    setupDone < 4 -> "Dokończ konfigurację" to "Gotowe $setupDone z 4 kroków – bez tego nagrywanie nie ruszy samo"
                    else -> "Czekam na auto" to "Nagrywanie ruszy samo po połączeniu z ${car ?: "Bluetooth auta"}"
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (setupDone < 4) Color(0xFFFFC928) else Color(0xFF5EE0A0), 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (setupDone < 4) Button(
                        onClick = { page = Page.SETTINGS },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = HeroBottom)
                    ) { Text("Przejdź do ustawień") }
                    else OutlinedButton(
                        onClick = { TripControl.start(this@MainActivity); refresh++ },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start ręczny")
                    }
                }
            }
        }
    }

    /** Podgląd trwającej jazdy: prędkość, znak ograniczenia (prawy górny róg), czas i dystans tej jazdy. */
    @Composable
    private fun LivePanel(l: LiveTrip.State) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
        val over = l.limitKmh != null && l.speedKmh > l.limitKmh
        val pl = Locale("pl")
        val elapsedS = ((now - l.startMs) / 1000.0).coerceAtLeast(0.0)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(Color(0xFFFF5252), 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Ta jazda", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (l.gpsOk) "GPS ✓" else "GPS – szukam…",
                            color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%.0f".format(l.speedKmh),
                        color = if (over) Color(0xFFFF8A80) else Color.White,
                        style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold
                    )
                    Text("km/h – aktualna prędkość", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                }
                // znak ograniczenia – prawy górny róg
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).background(Color(0xFFD32F2F)).padding(8.dp)
                            .clip(CircleShape).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            l.limitKmh?.toString() ?: "?",
                            color = Color(0xFF111111), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        when {
                            l.limitKmh == null -> "brak danych"
                            l.limitIsUser -> "Twoja poprawka"
                            l.limitIsDefault -> "wg typu drogi"
                            else -> "ze znaku (OSM)"
                        },
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall
                    )
                    LimitFixButton(l)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip("Czas jazdy", formatDuration(elapsedS), Modifier.weight(1.4f))
                LiveChip("Dystans", "%.2f km".format(pl, l.distanceM / 1000), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChip("W limicie", "%.2f km".format(pl, l.okM / 1000), Modifier.weight(1f), SpeedColors[1])
                LiveChip("Ponad limitem", "%.2f km".format(pl, l.overM / 1000), Modifier.weight(1f), SpeedColors[4])
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val avg = if (elapsedS > 0) l.distanceM / 1000 / (elapsedS / 3600) else 0.0
                LiveChip("Średnio", "%.0f km/h".format(avg), Modifier.weight(1f))
                LiveChip("Maks.", "%.0f km/h".format(l.maxSpeedKmh), Modifier.weight(1f))
                LiveChip("Bez limitu", "%.1f km".format(pl, l.noLimitM / 1000), Modifier.weight(1f), SpeedColors[0])
            }
            FilledTonalButton(onClick = { TripControl.stop(this@MainActivity); refresh++ }) { Text("Zakończ za minutę") }
        }
    }

    /** Rozwijany przycisk: prawidłowy limit na drodze, po której teraz jedziesz. */
    @Composable
    private fun LimitFixButton(l: LiveTrip.State) {
        var open by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { open = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            modifier = Modifier.padding(top = 4.dp).height(36.dp)
        ) { Text("Popraw ▾", style = MaterialTheme.typography.labelLarge) }

        if (open) {
            // duże znaki – łatwe do trafienia jednym dotknięciem
            androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Prawidłowy limit tutaj", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        LimitReports.COMMON_LIMITS.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                row.forEach { v ->
                                    SpeedSign(v, selected = v == l.limitKmh) {
                                        open = false
                                        val msg = LimitReports.submit(
                                            this@MainActivity, l.lat, l.lon, l.wayId, l.limitKmh, v, Prefs.sendOsmNotes(this@MainActivity)
                                        )
                                        toast(msg)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("Anuluj", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    /** Okrągły znak ograniczenia prędkości (czerwony pierścień, czarna liczba). */
    @Composable
    private fun SpeedSign(kmh: Int, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFFD32F2F)).padding(9.dp)
                    .clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    kmh.toString(), color = Color(0xFF111111), fontWeight = FontWeight.Black,
                    style = if (kmh >= 100) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
                )
            }
        }
    }

    @Composable
    private fun LiveChip(label: String, value: String, modifier: Modifier, accent: Color? = null) {
        Column(
            modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accent != null) { Dot(accent, 8.dp); Spacer(Modifier.width(5.dp)) }
                Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
            }
            Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SummaryTiles(s: TripRepository.Summary) {
        val pl = Locale("pl")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Dystans", "%.1f km".format(pl, s.km), Modifier.weight(1f))
                StatTile("Czas jazdy", formatDuration(s.seconds), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("W limicie", "%.1f km".format(pl, s.okKm), Modifier.weight(1f), accent = SpeedColors[1],
                    note = pctOf(s.okKm, s.km))
                StatTile("Ponad limitem", "%.1f km".format(pl, s.overKm), Modifier.weight(1f), accent = SpeedColors[4],
                    note = pctOf(s.overKm, s.km))
            }
            DistanceBar(s.kmByCat)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Ponad limitem (czas)", "%.0f%%".format(s.overPct), Modifier.weight(1f),
                    accent = severityColor(s.overPct), note = "> +20 km/h: %.0f%%".format(s.over20Pct))
                StatTile("Ostre hamowania", "%.1f".format(pl, s.brakesPer100), Modifier.weight(1f), note = "na 100 km")
            }
        }
    }

    private fun pctOf(part: Double, total: Double) =
        if (total > 0) "%.0f%% dystansu".format(part * 100 / total) else null

    /** Pasek: jaka część dystansu w której kategorii prędkości. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DistanceBar(cats: DoubleArray) {
        val total = cats.sum()
        if (total <= 0) return
        val labels = listOf("bez limitu", "w limicie", "+0–10", "+10–20", "+20–30", ">+30")
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dystans wg prędkości względem limitu", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                    // kolejność: w limicie, przekroczenia rosnąco, na końcu brak limitu
                    listOf(1, 2, 3, 4, 5, 0).forEach { i ->
                        if (cats[i] > 0) Box(Modifier.weight(cats[i].toFloat()).fillMaxHeight().background(SpeedColors[i]))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 2, 3, 4, 5, 0).forEach { i ->
                        if (cats[i] >= 0.05) Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(SpeedColors[i], 8.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("${labels[i]} %.1f km".format(Locale("pl"), cats[i]), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatTile(label: String, value: String, modifier: Modifier, accent: Color? = null, note: String? = null) {
        Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (accent != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun severityColor(overPct: Double) = when {
        overPct < 5 -> SpeedColors[1]
        overPct < 15 -> SpeedColors[2]
        overPct < 30 -> SpeedColors[3]
        else -> SpeedColors[4]
    }

    /** Miniatura trasy ładowana w tle (kształt zapisany w .json albo liczony z GPX). */
    @Composable
    private fun rememberThumb(trip: TripRepository.Trip, r: Int): List<ThumbPoint>? {
        val thumb by produceState<List<ThumbPoint>?>(null, trip.gpx.name, if (trip.recording) r else 0) {
            value = withContext(Dispatchers.IO) { runCatching { TripRepository.thumbnail(this@MainActivity, trip.gpx) }.getOrNull() }
        }
        return thumb
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LatestTripCard(trip: TripRepository.Trip, r: Int) {
        Card(
            onClick = { openTrip(trip) },
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                // trasa na prawdziwej mapie (bez internetu: z mapy offline albo sam kształt trasy)
                if (trip.recording) {
                    val track by LiveTrip.track.collectAsState()
                    val live by LiveTrip.state.collectAsState()
                    LiveMap(track, live, Modifier.matchParentSize())
                } else key(trip.gpx.name) {
                    MapPreview(
                        trip.gpx,
                        background = MaterialTheme.colorScheme.surface,
                        gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.matchParentSize()
                    )
                }
                // warstwa nad mapą: dotknięcie otwiera pełny ekran
                Box(Modifier.matchParentSize().clickable { openTrip(trip) })
                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        "▶  Mapa i odtwarzanie",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            TripInfo(trip, Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TripRow(trip: TripRepository.Trip, r: Int) {
        val thumb = rememberThumb(trip, r)
        Card(
            onClick = { openTrip(trip) },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RouteThumb(
                    thumb,
                    background = MaterialTheme.colorScheme.surface,
                    gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)),
                    lineWidth = 2.dp, padding = 8.dp
                )
                Spacer(Modifier.width(12.dp))
                TripInfo(trip, Modifier.weight(1f))
            }
        }
    }

    /** Tytuł, liczby i znacznik przekroczeń + menu ⋮. */
    @Composable
    private fun TripInfo(trip: TripRepository.Trip, modifier: Modifier) {
        val s: JSONObject? = trip.stats
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(trip.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (s == null) {
                    Text(
                        if (trip.recording) "● Nagrywanie trwa" else "Brak danych o trasie",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (trip.recording) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "%.1f km · %s · max %.0f km/h".format(
                            Locale("pl"), s.optDouble("distanceKm", 0.0),
                            formatDuration(s.optDouble("durationMin", 0.0) * 60), s.optDouble("maxSpeedKmh", 0.0)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val cats = TripStats.kmByCategory(s)
                    val overKm = cats[2] + cats[3] + cats[4] + cats[5]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(SpeedColors[1], 8.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("%.1f km".format(Locale("pl"), cats[1]), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(10.dp))
                        Dot(if (overKm > 0.05) SpeedColors[4] else SpeedColors[1], 8.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "ponad limitem %.1f km".format(Locale("pl"), overKm) +
                                (s.optInt("harshBrakes").takeIf { it > 0 }?.let { " · hamowania: $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            TripMenu(trip)
        }
    }

    @Composable
    private fun TripMenu(trip: TripRepository.Trip) {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Więcej") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Udostępnij GPX") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = {
                        open = false
                        runCatching { GpxExport.share(this@MainActivity, trip.gpx) }.onFailure { toast("Błąd: ${it.message}") }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Usuń trasę") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    enabled = !trip.recording,
                    onClick = { open = false; tripToDelete = trip }
                )
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🚗", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text("Nie ma jeszcze tras", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Wsiądź do auta – trasa zapisze się sama po połączeniu telefonu z Bluetooth samochodu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // =====================================================================
    // Statystyki (wszystkie jazdy)
    // =====================================================================

    private enum class Period(val label: String, val days: Int?) {
        WEEK("7 dni", 7), MONTH("30 dni", 30), YEAR("Rok", 365), ALL("Wszystko", null)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun StatsScreen() {
        var period by remember { mutableStateOf(Period.MONTH) }
        val all = remember { TripRepository.list(this).filter { it.stats != null } }
        val now = System.currentTimeMillis()
        val trips = remember(period) {
            val from = period.days?.let { now - it * 86_400_000L }
            all.filter { from == null || it.stats!!.optLong("startMs", 0) >= from }
        }
        val summary = remember(trips) { TripRepository.summary(trips) }
        val pl = Locale("pl")

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Statystyki") },
                    navigationIcon = {
                        IconButton(onClick = { page = Page.HOME }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć") }
                    }
                )
            }
        ) { pad ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Period.entries.forEach { p ->
                            FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label) })
                        }
                    }
                }
                if (trips.isEmpty()) {
                    item { Text("Brak jazd w tym okresie.", Modifier.padding(16.dp)) }
                    return@LazyColumn
                }
                val maxSpeed = trips.maxOf { it.stats!!.optDouble("maxSpeedKmh", 0.0) }
                val longest = trips.maxOf { it.stats!!.optDouble("distanceKm", 0.0) }
                val avgSpeed = if (summary.seconds > 0) summary.km / (summary.seconds / 3600) else 0.0
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Jazdy", summary.trips.toString(), Modifier.weight(1f))
                            StatTile("Dystans", "%.1f km".format(pl, summary.km), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Czas w aucie", formatDuration(summary.seconds), Modifier.weight(1f))
                            StatTile("Średnia prędkość", "%.0f km/h".format(avgSpeed), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("W limicie", "%.1f km".format(pl, summary.okKm), Modifier.weight(1f),
                                accent = SpeedColors[1], note = pctOf(summary.okKm, summary.km))
                            StatTile("Ponad limitem", "%.1f km".format(pl, summary.overKm), Modifier.weight(1f),
                                accent = SpeedColors[4], note = pctOf(summary.overKm, summary.km))
                        }
                    }
                }
                item { DistanceBar(summary.kmByCat) }
                item { SectionTitle(if (period == Period.WEEK || period == Period.MONTH) "Kilometry dziennie" else "Kilometry miesięcznie") }
                item { KmChart(trips, period) }
                item { SectionTitle("Prędkość i bezpieczeństwo") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Maks. prędkość", "%.0f km/h".format(maxSpeed), Modifier.weight(1f))
                            StatTile("Najdłuższa jazda", "%.1f km".format(pl, longest), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Ponad limitem (czas)", "%.0f%%".format(summary.overPct), Modifier.weight(1f),
                                accent = severityColor(summary.overPct), note = "> +20 km/h: %.0f%%".format(summary.over20Pct))
                            StatTile("Ostre hamowania", "%.1f".format(pl, summary.brakesPer100), Modifier.weight(1f), note = "na 100 km")
                        }
                    }
                }
            }
        }
    }

    /** Słupki kilometrów: zielony = w limicie, czerwony = ponad, szary = bez limitu. */
    @Composable
    private fun KmChart(trips: List<TripRepository.Trip>, period: Period) {
        val daily = period == Period.WEEK || period == Period.MONTH
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        // klucz kubełka -> [ok, over, none]
        val buckets = LinkedHashMap<java.time.LocalDate, DoubleArray>()
        if (daily) {
            for (i in (period.days!! - 1) downTo 0) buckets[today.minusDays(i.toLong())] = DoubleArray(3)
        } else {
            val first = trips.minOf { it.stats!!.optLong("startMs") }
            var m = java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate().withDayOfMonth(1)
            if (period == Period.YEAR) m = maxOf(m, today.minusMonths(11).withDayOfMonth(1))
            while (!m.isAfter(today)) { buckets[m] = DoubleArray(3); m = m.plusMonths(1) }
        }
        trips.forEach { t ->
            val js = t.stats!!
            var d = java.time.Instant.ofEpochMilli(js.optLong("startMs")).atZone(zone).toLocalDate()
            if (!daily) d = d.withDayOfMonth(1)
            val b = buckets[d] ?: return@forEach
            val c = TripStats.kmByCategory(js)
            b[0] += c[1]; b[1] += c[2] + c[3] + c[4] + c[5]; b[2] += c[0]
        }
        val maxKm = buckets.values.maxOfOrNull { it.sum() }?.coerceAtLeast(1.0) ?: 1.0
        val labelFmt = java.time.format.DateTimeFormatter.ofPattern(if (daily) "d.MM" else "LLL", Locale("pl"))
        val keys = buckets.keys.toList()

        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp)) {
                Text("najwięcej: %.1f km".format(Locale("pl"), maxKm), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 6.dp)) {
                    val n = buckets.size
                    val slot = size.width / n
                    val bw = (slot * 0.7f).coerceAtLeast(2f)
                    buckets.values.forEachIndexed { i, v ->
                        var y = size.height
                        listOf(0 to SpeedColors[1], 1 to SpeedColors[4], 2 to SpeedColors[0]).forEach { (k, col) ->
                            val h = (v[k] / maxKm * size.height).toFloat()
                            if (h > 0) {
                                drawRect(col, topLeft = androidx.compose.ui.geometry.Offset(i * slot + (slot - bw) / 2, y - h),
                                    size = androidx.compose.ui.geometry.Size(bw, h))
                                y -= h
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(keys.first(), keys[keys.size / 2], keys.last()).distinct().forEach {
                        Text(it.format(labelFmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // =====================================================================
    // Pomiary: g-metr, 0–100, 100–150, Vmax, odcinki
    // =====================================================================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PerfScreen() {
        val perf by Performance.state.collectAsState()
        val live by LiveTrip.state.collectAsState()
        val recording = live != null
        val gforce = remember { GForce(this@MainActivity) }
        val g by gforce.g.collectAsState()
        val pl = Locale("pl")
        LaunchedEffect(Unit) { Performance.init(this@MainActivity) }

        // czujniki tylko, gdy ekran jest otwarty
        DisposableEffect(Unit) {
            gforce.start()
            onDispose { gforce.stop() }
        }
        // uczenie kierunku jazdy z prędkości GPS
        val prev = remember { doubleArrayOf(-1.0, 0.0) }   // [czas s, prędkość km/h]
        LaunchedEffect(live?.fixTimeMs) {
            val l = live ?: return@LaunchedEffect
            val t = l.fixTimeMs / 1000.0
            if (prev[0] > 0 && t - prev[0] in 0.2..3.0) gforce.onGpsAccel((l.speedKmh - prev[1]) / 3.6 / (t - prev[0]), l.speedKmh)
            prev[0] = t; prev[1] = l.speedKmh
        }

        // przyspieszenie z czujników do pomiaru (gdy g-metr jest skalibrowany)
        LaunchedEffect(g) { if (g.calibrated) Performance.setSensorG(g.gLong) }

        val m = perf.measure
        if (m != null) {
            MeasureScreen(m, g, recording)
            return
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pomiary") },
                    navigationIcon = {
                        IconButton(onClick = { page = Page.HOME }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć") }
                    }
                )
            }
        ) { pad ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0x33FFC928)) {
                        Text(
                            "⚠ Pomiary przyspieszenia i prędkości wykonuj tylko tam, gdzie jest to legalne i bezpieczne (np. tor). " +
                                "Nie obsługuj telefonu w czasie jazdy – wyniki zapisują się same.",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (!recording) item {
                    Button(onClick = { TripControl.start(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Włącz nagrywanie – pomiary działają podczas jazdy")
                    }
                }

                // ---- prędkość + Vmax ----
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Prędkość", "%.0f km/h".format(perf.speed), Modifier.weight(1f))
                        StatTile("Vmax (sesja)", "%.0f km/h".format(perf.vmax), Modifier.weight(1f))
                    }
                }

                // ---- przyspieszenia ----
                item { SectionTitle("Przyspieszenie") }
                item {
                    Text(
                        "Naciśnij START, zanim ruszysz. Aplikacja poprowadzi Cię krok po kroku, pokaże prędkość, " +
                            "przyspieszenie i wykres na żywo, a na mecie – raport.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item { StartCard("0–100 km/h", "start z miejsca", bestOf(perf.runs, "0–100")) { armMeasure(0, 100, recording) } }
                item { StartCard("100–150 km/h", "rozpędzanie w ruchu", bestOf(perf.runs, "100–150")) { armMeasure(100, 150, recording) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Performance.resetSession() }) { Text("Reset sesji") }
                        TextButton(onClick = { Performance.clearRuns() }) { Text("Wyczyść wyniki", color = MaterialTheme.colorScheme.error) }
                    }
                }

                // ---- g-metr ----
                item { SectionTitle("Przeciążenia (g)") }
                item { GMeterCard(g, gforce) }

                // ---- odcinki ----
                item { SectionTitle("Odcinki pomiarowe") }
                item {
                    SettingsGroup {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            perf.segmentName?.let { name ->
                                Text("⏱ $name: " + formatDuration(perf.segmentElapsed ?: 0.0),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Ustaw start i metę w miejscach, gdzie stoisz (na postoju). Potem każdy przejazd przez start " +
                                    "uruchomi stoper, a przez metę – zapisze czas.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            val l = live
                            if (perf.newStart == null) {
                                OutlinedButton(enabled = l != null && l.gpsOk, onClick = { l?.let { Performance.setNewStart(it.lat, it.lon) } }) {
                                    Text("Ustaw START tutaj")
                                }
                            } else {
                                Text("Start zapisany. Podjedź do mety i ustaw ją.", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = l != null && l.gpsOk, onClick = { l?.let { Performance.finishNewSegment(it.lat, it.lon) } }) {
                                        Text("Ustaw METĘ tutaj")
                                    }
                                    TextButton(onClick = { Performance.cancelNewSegment() }) { Text("Anuluj") }
                                }
                            }
                            perf.segments.forEach { seg ->
                                val best = bestOf(perf.runs, "Odcinek: ${seg.name}")
                                val len = DisplayTrack.distM(seg.sLat, seg.sLon, seg.eLat, seg.eLon) / 1000
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(seg.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "%.1f km w linii prostej · najlepszy: %s".format(pl, len, best?.let { formatDuration(it.seconds) } ?: "–"),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { Performance.deleteSegment(seg) }) { Icon(Icons.Filled.Delete, contentDescription = "Usuń odcinek") }
                                }
                            }
                        }
                    }
                }

                // ---- historia ----
                if (perf.runs.isNotEmpty()) {
                    item { SectionTitle("Ostatnie wyniki") }
                    items(perf.runs.take(30)) { r ->
                        val fmt = java.text.SimpleDateFormat("d.MM HH:mm", pl)
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (r.report != null) Modifier.clickable { Performance.showReport(r) } else Modifier)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(r.type + (r.note?.let { " · $it" } ?: "") + (if (r.report != null) "  ›" else ""),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                (if (r.type.startsWith("Odcinek")) formatDuration(r.seconds) else "%.2f s".format(r.seconds)) +
                                    "  " + fmt.format(java.util.Date(r.timeMs)),
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bestOf(runs: List<Performance.Run>, type: String) = runs.filter { it.type == type }.minByOrNull { it.seconds }

    private fun armMeasure(from: Int, to: Int, recording: Boolean) {
        if (!recording) TripControl.start(this)
        Performance.arm(from, to)
    }

    @Composable
    private fun StartCard(title: String, sub: String, best: Performance.Run?, onStart: () -> Unit) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Najlepszy: " + (best?.let { "%.2f s".format(it.seconds) } ?: "–"), style = MaterialTheme.typography.labelLarge)
                }
                Button(onClick = onStart, shape = CircleShape, modifier = Modifier.size(84.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("START", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    /** Pełny ekran pomiaru: instrukcja → stoper, prędkość, g, wykres na żywo → raport. */
    @Composable
    private fun MeasureScreen(m: Performance.Measure, g: GForce.G, recording: Boolean) {
        BackHandler { Performance.closeMeasure() }
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

        // sygnał dźwiękowy: start stopera i meta (nie trzeba patrzeć na telefon)
        var lastPhase by remember { mutableStateOf(m.phase) }
        LaunchedEffect(m.phase) {
            if (m.phase != lastPhase && m.phase in listOf(Performance.Phase.READY, Performance.Phase.RUNNING, Performance.Phase.DONE, Performance.Phase.ABORTED)) {
                runCatching {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
                    tg.startTone(
                        when (m.phase) {
                            Performance.Phase.DONE -> android.media.ToneGenerator.TONE_PROP_ACK
                            Performance.Phase.ABORTED -> android.media.ToneGenerator.TONE_PROP_NACK
                            else -> android.media.ToneGenerator.TONE_PROP_BEEP
                        }, 300
                    )
                    delay(600); tg.release()
                }
            }
            lastPhase = m.phase
        }

        // płynny stoper między odczytami GPS
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(m.phase) {
            while (m.phase == Performance.Phase.RUNNING) { now = System.currentTimeMillis(); delay(50) }
        }
        val elapsed = if (m.phase == Performance.Phase.RUNNING)
            m.elapsed + ((now - m.updatedAtMs).coerceIn(0, 1500)) / 1000.0 else m.elapsed
        val gNow = if (g.calibrated) g.gLong else m.g

        val (accent, banner) = when (m.phase) {
            Performance.Phase.WAIT_STOP -> Color(0xFFFFA000) to (if (m.from == 0) "ZATRZYMAJ AUTO" else "ZWOLNIJ")
            Performance.Phase.READY -> Color(0xFF2E7D32) to (if (m.from == 0) "GOTOWY – RUSZAJ!" else "GOTOWY – PRZYSPIESZ!")
            Performance.Phase.RUNNING -> Color(0xFF1565C0) to "MIERZĘ…"
            Performance.Phase.DONE -> Color(0xFF2E7D32) to "RAPORT"
            Performance.Phase.ABORTED -> Color(0xFFC62828) to "PRZERWANO"
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { Performance.closeMeasure() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zamknij") }
                    Text("${m.type} km/h", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = accent, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(banner, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        if (m.phase != Performance.Phase.DONE)
                            Text(m.message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!recording && m.phase != Performance.Phase.DONE)
                    Text("Czekam na GPS (nagrywanie się uruchamia)…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (m.phase == Performance.Phase.DONE && m.result != null) {
                    MeasureReport(m, m.result)
                } else {
                    // stoper
                    Text(
                        "%.2f s".format(elapsed), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (m.phase == Performance.Phase.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // prędkość + postęp do celu
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.0f".format(m.speed), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(" km/h", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                        Spacer(Modifier.weight(1f))
                        Text("cel ${m.to} km/h", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp))
                    }
                    LinearProgressIndicator(
                        progress = { ((m.speed - m.from) / (m.to - m.from)).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                        color = accent,
                    )
                    // przyspieszenie
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Przyspieszenie", style = MaterialTheme.typography.labelLarge)
                            Text(if (g.calibrated) "z czujników telefonu" else "z prędkości GPS (skalibruj g-metr dla dokładności)",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("%.2f g".format(gNow), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (gNow / 1.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = Color(0xFFEF6C00),
                    )
                    SpeedChart(m.samples, m.from, m.to, maxOf(elapsed, 5.0), Modifier.fillMaxWidth().height(200.dp))

                    if (m.phase == Performance.Phase.ABORTED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { Performance.arm(m.from, m.to) }, modifier = Modifier.weight(1f)) { Text("Spróbuj ponownie") }
                            OutlinedButton(onClick = { Performance.closeMeasure() }, modifier = Modifier.weight(1f)) { Text("Zamknij") }
                        }
                    } else {
                        OutlinedButton(onClick = { Performance.closeMeasure() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text("PRZERWIJ", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MeasureReport(m: Performance.Measure, run: Performance.Run) {
        val rep = run.report
        val pl = Locale("pl")
        Text("%.2f s".format(run.seconds), Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.primary)
        Text(java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", pl).format(java.util.Date(run.timeMs)), Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        rep?.prevBest?.let { pb ->
            val d = run.seconds - pb
            Surface(shape = RoundedCornerShape(12.dp), color = if (d < 0) Color(0x332E7D32) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (d < 0) "🏆 Nowy rekord! Lepiej o %.2f s (poprzednio %.2f s)".format(-d, pb)
                    else "Do rekordu zabrakło %.2f s (rekord %.2f s)".format(d, pb),
                    Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold
                )
            }
        } ?: if (rep != null) Text("Pierwszy wynik w tej kategorii – to Twój rekord.", fontWeight = FontWeight.SemiBold) else Unit
        if (rep == null) return

        SpeedChart(rep.samples, m.from, m.to, run.seconds, Modifier.fillMaxWidth().height(220.dp))

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Międzyczasy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                rep.splits.forEach { (v, t) -> ReportLine("${m.from}–$v km/h", "%.2f s".format(t)) }
                ReportLine("${m.from}–${m.to} km/h", "%.2f s".format(run.seconds), bold = true)
                HorizontalDivider()
                Text("Szczegóły", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ReportLine("Dystans rozpędzania", "%.0f m".format(rep.distanceM))
                ReportLine("Średnie przyspieszenie", "%.2f g (%.1f m/s²)".format(rep.avgG, rep.avgG * 9.81))
                ReportLine("Maks. przyspieszenie", "%.2f g".format(rep.maxG))
                Text(if (rep.gFromSensor) "Przyspieszenie z czujników telefonu." else "Przyspieszenie z prędkości GPS (orientacyjne).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { Performance.arm(m.from, m.to) }, modifier = Modifier.weight(1f)) { Text("Jeszcze raz") }
            OutlinedButton(onClick = { Performance.closeMeasure() }, modifier = Modifier.weight(1f)) { Text("Zamknij") }
        }
    }

    @Composable
    private fun ReportLine(label: String, value: String, bold: Boolean = false) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }

    /** Wykres: prędkość (niebieska, lewa oś) i przyspieszenie g (pomarańczowe, 0–1 g) w funkcji czasu. */
    @Composable
    private fun SpeedChart(samples: List<Performance.Sample>, from: Int, to: Int, tMax: Double, modifier: Modifier) {
        val grid = MaterialTheme.colorScheme.outlineVariant
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        val speedColor = Color(0xFF1E88E5)
        val gColor = Color(0xFFEF6C00)
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val style = MaterialTheme.typography.labelSmall.copy(color = labelColor)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(start = 34.dp, end = 12.dp, top = 12.dp, bottom = 22.dp)) {
                val w = size.width; val h = size.height
                val tSpan = if (tMax > 0) tMax else 1.0
                fun x(t: Double) = (t / tSpan * w).toFloat()
                fun yv(v: Double) = (h - (v - from) / (to - from) * h).toFloat().coerceIn(0f, h)
                fun yg(gv: Double) = (h - gv.coerceIn(0.0, 1.0) * h).toFloat()
                val step = if (to - from <= 50) 10 else 20
                var v = from
                while (v <= to) {
                    drawLine(grid, androidx.compose.ui.geometry.Offset(0f, yv(v.toDouble())), androidx.compose.ui.geometry.Offset(w, yv(v.toDouble())), 1f)
                    drawText(measurer, "$v", androidx.compose.ui.geometry.Offset(-30.dp.toPx(), yv(v.toDouble()) - 8.dp.toPx()), style)
                    v += step
                }
                val tStep = if (tSpan <= 12) 2 else 5
                var tt = 0
                while (tt <= tSpan) {
                    drawText(measurer, "${tt}s", androidx.compose.ui.geometry.Offset(x(tt.toDouble()) - 6.dp.toPx(), h + 4.dp.toPx()), style)
                    tt += tStep
                }
                if (samples.size >= 2) {
                    val gp = androidx.compose.ui.graphics.Path()
                    samples.forEachIndexed { i, s -> if (i == 0) gp.moveTo(x(s.t), yg(s.g)) else gp.lineTo(x(s.t), yg(s.g)) }
                    drawPath(gp, gColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    val sp = androidx.compose.ui.graphics.Path()
                    samples.forEachIndexed { i, s -> if (i == 0) sp.moveTo(x(s.t), yv(s.v)) else sp.lineTo(x(s.t), yv(s.v)) }
                    drawPath(sp, speedColor, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val last = samples.last()
                    drawCircle(speedColor, 6.dp.toPx(), androidx.compose.ui.geometry.Offset(x(last.t), yv(last.v)))
                }
                drawText(measurer, "— km/h", androidx.compose.ui.geometry.Offset(w - 110.dp.toPx(), 0f), style.copy(color = speedColor))
                drawText(measurer, "— g", androidx.compose.ui.geometry.Offset(w - 46.dp.toPx(), 0f), style.copy(color = gColor))
            }
        }
    }

    /** „Kulka g”: kropka pokazuje bieżące przeciążenie (w górę = przyspieszanie, w dół = hamowanie). */
    @Composable
    private fun GMeterCard(g: GForce.G, gforce: GForce) {
        val ring = MaterialTheme.colorScheme.outlineVariant
        val dot = MaterialTheme.colorScheme.primary
        val label = MaterialTheme.colorScheme.onSurfaceVariant
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!gforce.available) {
                    Text("Ten telefon nie ma czujników potrzebnych do pomiaru g.")
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Canvas(Modifier.size(150.dp)) {
                        val c = center; val r = size.minDimension / 2
                        val scale = r / 1.2f                            // pełny promień = 1,2 g
                        listOf(0.5f, 1.0f).forEach { drawCircle(ring, it * scale, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f)) }
                        drawLine(ring, androidx.compose.ui.geometry.Offset(c.x - r, c.y), androidx.compose.ui.geometry.Offset(c.x + r, c.y), 1f)
                        drawLine(ring, androidx.compose.ui.geometry.Offset(c.x, c.y - r), androidx.compose.ui.geometry.Offset(c.x, c.y + r), 1f)
                        val x = (g.gLat.coerceIn(-1.2, 1.2) * scale).toFloat()
                        val y = (-g.gLong.coerceIn(-1.2, 1.2) * scale).toFloat()
                        drawCircle(dot, 11f, androidx.compose.ui.geometry.Offset(c.x + x, c.y + y))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("%.2f g".format(g.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (g.calibrated) {
                            Text("wzdłużnie %+.2f · w bok %+.2f".format(g.gLong, g.gLat), style = MaterialTheme.typography.labelSmall, color = label)
                            Text("Maks. hamowanie: %.2f g".format(g.maxBrake), style = MaterialTheme.typography.bodySmall)
                            Text("Maks. przyspieszenie: %.2f g".format(g.maxAccel), style = MaterialTheme.typography.bodySmall)
                            Text("Zakręt w lewo: %.2f g".format(g.maxLeft), style = MaterialTheme.typography.bodySmall)
                            Text("Zakręt w prawo: %.2f g".format(g.maxRight), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Uczę się, jak telefon leży w aucie (${g.learnSamples}/${GForce.MIN_SAMPLES}). " +
                                    "Kilka razy normalnie przyspiesz i zahamuj podczas nagrywania.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { gforce.resetPeaks() }) { Text("Reset maksimów") }
                    TextButton(onClick = { gforce.recalibrate() }) { Text("Kalibruj od nowa") }
                }
                Text(
                    "Telefon musi być stabilnie zamocowany w uchwycie. Po zmianie ułożenia telefonu użyj „Kalibruj od nowa”.",
                    style = MaterialTheme.typography.labelSmall, color = label
                )
            }
        }
    }

    // =====================================================================
    // Ustawienia
    // =====================================================================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        val r = refresh
        val car = remember(r) { carName() }
        val offlineDb = remember(r) { SpeedLimitProvider.offlineFile(this).exists() }
        val tiles = remember(r) { OnlineLimitCache.get(this).tileCount() }
        val version = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "" }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ustawienia") },
                    navigationIcon = {
                        IconButton(onClick = { page = Page.HOME }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć")
                        }
                    }
                )
            }
        ) { pad ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { SectionTitle("Automatyczne nagrywanie") }
                item {
                    SettingsGroup {
                        SetupRow("Lokalizacja i Bluetooth", "Pozycja GPS i wykrywanie auta", baseOk()) { requestBasePerms() }
                        SetupRow("Lokalizacja „Zawsze”", "Nagrywanie bez otwierania aplikacji", bgOk(), enabled = baseOk()) {
                            bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        SetupRow(
                            "Bluetooth auta", car ?: "Wybierz samochód z listy sparowanych", car != null,
                            enabled = baseOk(), action = if (car == null) "Wybierz" else "Zmień"
                        ) { showCarPicker = true }
                        SetupRow("Bez ograniczeń baterii", "Android nie zatrzyma nagrywania", batteryExempt(), enabled = car != null) {
                            requestBatteryExemption()
                        }
                    }
                }

                item { SectionTitle("Ograniczenia prędkości") }
                item {
                    SettingsGroup {
                        ListItem(
                            headlineContent = { Text(if (offlineDb) "Baza offline" else "Pobierane automatycznie") },
                            supportingContent = {
                                Text(
                                    if (offlineDb) "Własna baza ograniczeń zaimportowana z pliku"
                                    else "Z OpenStreetMap podczas jazdy · zapisane obszary: $tiles"
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        var osmNotes by remember { mutableStateOf(Prefs.sendOsmNotes(this@MainActivity)) }
                        ListItem(
                            headlineContent = { Text("Wysyłaj poprawki do OpenStreetMap") },
                            supportingContent = {
                                Text("Poprawka z przycisku „Popraw ▾” działa od razu u Ciebie. Gdy włączone – trafia też jako anonimowa notatka do OSM, gdzie mapujący sprawdzą znak. Twoich poprawek: ${OnlineLimitCache.get(this@MainActivity).overrideCount()}")
                            },
                            trailingContent = {
                                Switch(checked = osmNotes, onCheckedChange = { osmNotes = it; Prefs.setSendOsmNotes(this@MainActivity, it) })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Importuj bazę offline") },
                            supportingContent = { Text("Opcjonalnie – plik speedlimits.sqlite (patrz README)") },
                            trailingContent = { TextButton(onClick = { importDb.launch(arrayOf("*/*")) }) { Text("Wybierz plik") } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }

                item { SectionTitle("Moje zgłoszenia limitów") }
                item { ReportsSection() }

                item { SectionTitle("Mapa offline") }
                item { OfflineMapSection() }

                item { SectionTitle("Nagrywanie ręczne") }
                item {
                    SettingsGroup {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { TripControl.start(this@MainActivity); toast("Nagrywanie rozpoczęte") }) { Text("Start") }
                            OutlinedButton(onClick = { TripControl.stop(this@MainActivity); toast("Nagrywanie zakończy się za minutę") }) { Text("Stop") }
                        }
                    }
                }

                item { SectionTitle("Dane") }
                item {
                    SettingsGroup {
                        ListItem(
                            headlineContent = { Text("Usuń wszystkie trasy", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text("Pliki GPX i statystyki – nie da się tego cofnąć") },
                            leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.padding(vertical = 2.dp),
                            trailingContent = { TextButton(onClick = { confirmDeleteAll = true }) { Text("Usuń") } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }

                item {
                    Text(
                        "Drive Monitor $version · trasy: Android/data/pl.drivemonitor/files/trips",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ReportsSection() {
        LaunchedEffect(Unit) { LimitReports.load(this@MainActivity) }
        val reports by LimitReports.reports.collectAsState()
        val fmt = remember { java.text.SimpleDateFormat("d.MM HH:mm", Locale("pl")) }
        SettingsGroup {
            Column(Modifier.padding(vertical = 6.dp)) {
                if (reports.isEmpty()) {
                    Text(
                        "Brak zgłoszeń. W trakcie jazdy użyj „Popraw ▾” pod znakiem ograniczenia.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall
                    )
                }
                reports.take(30).forEach { r ->
                    ListItem(
                        headlineContent = {
                            Text((r.shownLimit?.let { "$it → " } ?: "") + "${r.correctKmh} km/h · " + fmt.format(java.util.Date(r.id)))
                        },
                        supportingContent = {
                            Text(
                                when (r.status) {
                                    "sent" -> "Wysłano do OSM" + (r.noteId?.takeIf { it > 0 }?.let { " – notatka nr $it (dotknij, by otworzyć)" } ?: "")
                                    "queued" -> "Czeka na wysłanie" + (r.error?.let { " ($it)" } ?: "")
                                    "failed" -> "Nie wysłano: ${r.error ?: "nieznany błąd"}"
                                    else -> "Tylko lokalnie"
                                } + if (r.wayId == null) " · droga nierozpoznana" else "",
                                color = if (r.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Row {
                                if (r.status == "failed" || r.status == "local") {
                                    TextButton(onClick = { LimitReports.retry(this@MainActivity, r) }) {
                                        Text(if (r.status == "local") "Wyślij" else "Ponów")
                                    }
                                }
                                IconButton(onClick = { LimitReports.remove(this@MainActivity, r) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Usuń zgłoszenie i poprawkę")
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = (r.noteId ?: 0) > 0) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/note/${r.noteId}")))
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }

    /** Pobieranie mapy na jazdę bez internetu + instrukcja. */
    @Composable
    private fun OfflineMapSection() {
        val progress by OfflineMapDownloader.progress.collectAsState()
        val scope = rememberCoroutineScope()
        var regions by remember { mutableStateOf<Int?>(null) }
        val running = progress?.running == true
        LaunchedEffect(running) { OfflineMapDownloader.countRegions(this@MainActivity) { regions = it } }
        var showHelp by remember { mutableStateOf(false) }

        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pobierz okolicę, po której jeździsz – ekran trasy i podgląd na ekranie głównym pokażą wtedy mapę także bez internetu.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Pobrane okolice: " + (regions?.toString() ?: "…"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                progress?.let { p ->
                    if (p.running && p.total > 0) {
                        LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    } else if (p.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        p.message + if (p.running && p.total > 0) " (${p.done} z ${p.total} plików)" else "",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (running) {
                    OutlinedButton(onClick = { OfflineMapDownloader.cancel() }) { Text("Przerwij pobieranie") }
                } else {
                    Button(onClick = {
                        scope.launch {
                            val bbox = withContext(Dispatchers.IO) { tripsBbox() }
                            if (bbox == null) toast("Brak zapisanych tras – użyj „25 km wokół mnie”")
                            else OfflineMapDownloader.start(this@MainActivity, bbox, "okolica moich tras")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Pobierz okolicę moich tras") }
                    OutlinedButton(onClick = { downloadAroundMe() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Pobierz 25 km wokół mnie")
                    }
                    Row {
                        TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Ukryj instrukcję" else "Instrukcja") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            OfflineMapDownloader.deleteAll(this@MainActivity) { regions = 0; toast("Usunięto mapę offline") }
                        }) { Text("Usuń mapę", color = MaterialTheme.colorScheme.error) }
                    }
                }

                if (showHelp) {
                    Text(
                        """
                        Jak przygotować mapę na jazdę bez internetu:
                        1. Połącz się z Wi-Fi – okolica 25 km to zwykle 50–150 MB.
                        2. Wybierz „Pobierz 25 km wokół mnie” (potrzebna lokalizacja) albo „Pobierz okolicę moich tras” – obszar obejmujący wszystkie zapisane trasy.
                        3. Zostaw ekran otwarty do końca pobierania; postęp widać powyżej. Pobrane fragmenty zostają też po przerwaniu.
                        4. Gotowe: mapa tej okolicy działa bez internetu – na ekranie trasy i w podglądzie na ekranie głównym.

                        Pobierana jest mapa do poziomu ulic (zbliżenie 14) z ikonami i polskimi napisami. Przy większym zbliżeniu bez internetu mapa jest powiększana z poziomu 14.
                        Bardzo duży obszar (np. cała Polska) może zająć kilka GB – pobieraj okolice, po których jeździsz.
                        Mapa: © OpenStreetMap, OpenFreeMap.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    /** Prostokąt obejmujący wszystkie trasy + 3 km zapasu. */
    private fun tripsBbox(): DoubleArray? {
        var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
        var any = false
        TripRepository.list(this).forEach { t ->
            runCatching { TripRepository.thumbnail(this, t.gpx) }.getOrNull()?.forEach { p ->
                any = true
                s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lon); e = maxOf(e, p.lon)
            }
        }
        if (!any) return null
        val m = 3.0 / 111.32
        val mLon = m / kotlin.math.cos(Math.toRadians((s + n) / 2))
        return doubleArrayOf(s - m, w - mLon, n + m, e + mLon)
    }

    @SuppressLint("MissingPermission")
    private fun downloadAroundMe() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) { toast("Najpierw nadaj dostęp do lokalizacji"); return }
        com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) toast("Nie znam teraz Twojej pozycji – spróbuj za chwilę")
                else OfflineMapDownloader.start(this, OfflineMapDownloader.bboxAround(loc.latitude, loc.longitude, 25.0), "25 km wokół Ciebie")
            }
            .addOnFailureListener { toast("Nie udało się ustalić pozycji: ${it.message}") }
    }

    @Composable
    private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(content = content)
        }
    }

    @Composable
    private fun SetupRow(
        title: String, subtitle: String, done: Boolean,
        enabled: Boolean = true, action: String = "Zezwól", onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = {
                if (done) Icon(Icons.Filled.CheckCircle, null, tint = SpeedColors[1])
                else Icon(Icons.Filled.Warning, null, tint = Color(0xFFE0A000))
            },
            trailingContent = {
                if (!done || action == "Zmień") TextButton(onClick = onClick, enabled = enabled) { Text(action) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    // =====================================================================
    // Okna dialogowe
    // =====================================================================

    @Composable
    private fun CarPickerDialog() {
        val devices = remember { bondedDevices() }
        AlertDialog(
            onDismissRequest = { showCarPicker = false },
            title = { Text("Wybierz Bluetooth auta") },
            text = {
                if (devices.isEmpty()) {
                    Text("Brak sparowanych urządzeń. Sparuj najpierw telefon z autem w ustawieniach Bluetooth.")
                } else {
                    LazyColumn {
                        items(devices) { (name, mac) ->
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text(mac) },
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                    Prefs.saveCar(this@MainActivity, mac, name)
                                    showCarPicker = false
                                    refresh++
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCarPicker = false }) { Text("Anuluj") } }
        )
    }

    @Composable
    private fun DeleteTripDialog(trip: TripRepository.Trip) {
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Usunąć trasę?") },
            text = { Text("${trip.title}\nPlik GPX i statystyki zostaną usunięte z telefonu.") },
            confirmButton = {
                TextButton(onClick = {
                    TripRepository.delete(trip.gpx)
                    tripToDelete = null
                    refresh++
                }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { tripToDelete = null }) { Text("Anuluj") } }
        )
    }

    @Composable
    private fun DeleteAllDialog() {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Usunąć wszystkie trasy?") },
            text = { Text("Wszystkie zapisane pliki GPX i statystyki zostaną usunięte. Trwające nagrywanie zostanie zachowane.") },
            confirmButton = {
                TextButton(onClick = {
                    val n = TripRepository.deleteAll(this@MainActivity)
                    confirmDeleteAll = false
                    toast("Usunięto tras: $n")
                    refresh++
                }) { Text("Usuń wszystko", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Anuluj") } }
        )
    }
}
