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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private enum class Page { HOME, SETTINGS }

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
        enableEdgeToEdge()
        setContent {
            DriveTheme {
                BackHandler(enabled = page == Page.SETTINGS) { page = Page.HOME }
                when (page) {
                    Page.HOME -> HomeScreen()
                    Page.SETTINGS -> SettingsScreen()
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
        val trips = remember(r) { TripRepository.list(this) }
        val summary = remember(trips) { TripRepository.summary(trips) }
        val recording = TripRecorderService.currentFile != null
        val setupDone = remember(r) { setupDoneCount() }

        // w trakcie nagrywania odświeżamy listę co kilka sekund
        LaunchedEffect(recording) {
            while (recording) { delay(5000); refresh++ }
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

                if (summary.trips > 0) item { SummaryTiles(summary) }

                val latest = trips.firstOrNull()
                if (latest != null) {
                    item { SectionTitle(if (latest.recording) "Trwająca trasa" else "Ostatnia trasa") }
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

    /** Duża granatowa karta: co aplikacja teraz robi. */
    @Composable
    private fun StatusCard(recording: Boolean, setupDone: Int) {
        val car = carName()
        val (title, subtitle) = when {
            recording -> "Nagrywanie trasy" to "Zatrzyma się samo po rozłączeniu z autem"
            setupDone < 4 -> "Dokończ konfigurację" to "Gotowe $setupDone z 4 kroków – bez tego nagrywanie nie ruszy samo"
            else -> "Czekam na auto" to "Nagrywanie ruszy samo po połączeniu z ${car ?: "Bluetooth auta"}"
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom)))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            when {
                                recording -> Color(0xFFFF5252)
                                setupDone < 4 -> Color(0xFFFFC928)
                                else -> Color(0xFF5EE0A0)
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        recording -> FilledTonalButton(onClick = { TripControl.stop(this@MainActivity); refresh++ }) {
                            Text("Zakończ teraz")
                        }
                        setupDone < 4 -> Button(
                            onClick = { page = Page.SETTINGS },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = HeroBottom)
                        ) { Text("Przejdź do ustawień") }
                        else -> OutlinedButton(
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
    }

    @Composable
    private fun SummaryTiles(s: TripRepository.Summary) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Dystans", "%.0f km".format(Locale("pl"), s.km), Modifier.weight(1f))
                StatTile("Czas jazdy", "%.1f h".format(Locale("pl"), s.hours), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Ponad limitem", "%.0f%%".format(s.overPct), Modifier.weight(1f),
                    accent = severityColor(s.overPct), note = "> +20 km/h: %.0f%%".format(s.over20Pct)
                )
                StatTile("Ostre hamowania", "%.1f".format(Locale("pl"), s.brakesPer100), Modifier.weight(1f), note = "na 100 km")
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
            value = withContext(Dispatchers.IO) { runCatching { TripRepository.thumbnail(trip.gpx) }.getOrNull() }
        }
        return thumb
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LatestTripCard(trip: TripRepository.Trip, r: Int) {
        val thumb = rememberThumb(trip, r)
        Card(
            onClick = { openTrip(trip) },
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box {
                RouteThumb(
                    thumb,
                    background = MaterialTheme.colorScheme.surface,
                    gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                    lineWidth = 4.dp, padding = 22.dp
                )
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
                        "%.1f km · %.0f min · max %.0f km/h".format(
                            Locale("pl"), s.optDouble("distanceKm", 0.0), s.optDouble("durationMin", 0.0), s.optDouble("maxSpeedKmh", 0.0)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val over = s.optDouble("overPct_0", 0.0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(severityColor(over)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "ponad limitem %.0f%% czasu".format(over) +
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
                        ListItem(
                            headlineContent = { Text("Importuj bazę offline") },
                            supportingContent = { Text("Opcjonalnie – plik speedlimits.sqlite (patrz README)") },
                            trailingContent = { TextButton(onClick = { importDb.launch(arrayOf("*/*")) }) { Text("Wybierz plik") } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }

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
