package pl.drivemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private var refresh by mutableIntStateOf(0)
    private var showCarPicker by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        refresh++   // np. po powrocie z ustawień baterii
    }

    /** Sparowane urządzenia Bluetooth (nazwa, MAC), posortowane – zestaw auta jest wśród nich. */
    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<Pair<String, String>> = try {
        getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .map { (it.name ?: it.address) to it.address }
            .sortedBy { it.first.lowercase() }
    } catch (e: SecurityException) { emptyList() }

    private fun batteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    // Krok 1: lokalizacja na pierwszym planie + Bluetooth + powiadomienia
    private val basePerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    // Krok 2: lokalizacja w tle (Android wymaga osobnego pytania, po kroku 1)
    private val bgPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    // Opcjonalna baza offline: użytkownik wybiera plik .sqlite, kopiujemy go do folderu aplikacji
    private val importDb = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val target = SpeedLimitProvider.offlineFile(this)
        val tmp = java.io.File(target.parentFile, "import.tmp")
        val msg = runCatching {
            contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            val n = android.database.sqlite.SQLiteDatabase.openDatabase(
                tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db -> db.rawQuery("SELECT COUNT(*) FROM segments", null).use { it.moveToFirst(); it.getLong(0) } }
            tmp.renameTo(target)
            "Zaimportowano bazę: $n odcinków dróg"
        }.getOrElse { tmp.delete(); "To nie jest baza limitów DriveMonitor (${it.message})" }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        refresh++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Screen() {
        val r = refresh   // przebudowa po zmianie stanu
        val trips = remember(r) { TripRepository.list(this) }
        val summary = remember(trips) { TripRepository.summary(trips) }
        val baseOk = has(Manifest.permission.ACCESS_FINE_LOCATION) &&
                has(Manifest.permission.BLUETOOTH_CONNECT)
        val bgOk = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val car = Prefs.carName(this) ?: Prefs.carMac(this)
        val offlineDb = remember(r) { SpeedLimitProvider.offlineFile(this).exists() }
        val tiles = remember(r) { OnlineLimitCache.get(this).tileCount() }

        if (showCarPicker) CarPickerDialog()

        Scaffold { pad ->
            LazyColumn(
                Modifier.padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text("Drive Monitor", style = MaterialTheme.typography.headlineMedium) }

                item {
                    SetupStep("1. Uprawnienia podstawowe", baseOk) {
                        basePerms.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ))
                    }
                }
                item {
                    SetupStep("2. Lokalizacja „zawsze”", bgOk, enabled = baseOk) {
                        bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
                item {
                    SetupStep(
                        "3. Bluetooth auta" + (car?.let { ": $it" } ?: ""),
                        car != null, enabled = baseOk, actionLabel = if (car == null) "Wybierz" else "Zmień"
                    ) { showCarPicker = true }
                }
                item {
                    SetupStep("4. Bez ograniczeń baterii", batteryExempt(), enabled = car != null, actionLabel = "Zezwól") {
                        requestBatteryExemption()
                    }
                }
                item {
                    Column {
                        Text(
                            if (offlineDb) "✓ Limity prędkości: baza offline"
                            else "✓ Limity prędkości pobierane automatycznie z OpenStreetMap " +
                                "podczas jazdy (zapisane obszary: $tiles)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { importDb.launch(arrayOf("*/*")) }) {
                            Text("Importuj bazę offline (opcjonalnie)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { TripControl.start(this@MainActivity) }) { Text("Start ręczny") }
                        OutlinedButton(onClick = { TripControl.stop(this@MainActivity) }) { Text("Stop") }
                        TextButton(onClick = { refresh++ }) { Text("Odśwież") }
                    }
                }

                item { HorizontalDivider() }
                item { Text("Podsumowanie", style = MaterialTheme.typography.titleMedium) }
                items(summary.entries.toList()) { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k); Text(if (k == "Trasy") "%.0f".format(v) else "%.1f".format(v))
                    }
                }

                item { HorizontalDivider() }
                item { Text("Trasy", style = MaterialTheme.typography.titleMedium) }
                items(trips) { t -> TripRow(t.gpx, t.stats, t.recording) }
            }
        }
    }

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
                            TextButton(
                                onClick = {
                                    Prefs.saveCar(this@MainActivity, mac, name)
                                    showCarPicker = false
                                    refresh++
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(name)
                                    Text(mac, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCarPicker = false }) { Text("Anuluj") } }
        )
    }

    @Composable
    private fun SetupStep(
        label: String, done: Boolean, enabled: Boolean = true,
        actionLabel: String = "Nadaj", onClick: () -> Unit
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text((if (done) "✓ " else "○ ") + label, Modifier.weight(1f).padding(top = 10.dp))
            if (!done || actionLabel == "Zmień") {
                Button(onClick = onClick, enabled = enabled) { Text(actionLabel) }
            }
        }
    }

    @Composable
    private fun TripRow(gpx: File, s: org.json.JSONObject?, recording: Boolean) {
        Card(
            onClick = {
                startActivity(
                    android.content.Intent(this, TripMapActivity::class.java)
                        .putExtra(TripMapActivity.EXTRA_GPX, gpx.absolutePath)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(gpx.nameWithoutExtension.replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                if (s == null) {
                    Text(
                        if (recording) "● Nagrywanie trwa – dotknij, by zobaczyć dotychczasową trasę"
                        else "Brak danych o trasie",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "%.1f km · %.0f min · max %.0f km/h".format(
                            s.optDouble("distanceKm"), s.optDouble("durationMin"), s.optDouble("maxSpeedKmh")
                        )
                    )
                    Text(
                        "Ponad limit: %.0f%% czasu · >+20: %.0f%% · max +%.0f km/h · hamowania: %d".format(
                            s.optDouble("overPct_0"), s.optDouble("overPct_20"),
                            s.optDouble("maxOverKmh").coerceAtLeast(0.0), s.optInt("harshBrakes")
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
