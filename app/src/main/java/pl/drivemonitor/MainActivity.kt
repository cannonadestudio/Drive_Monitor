package pl.drivemonitor

import android.Manifest
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
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

    private val cdm by lazy { getSystemService(CompanionDeviceManager::class.java) }
    private var refresh by mutableIntStateOf(0)

    // Krok 1: lokalizacja na pierwszym planie + Bluetooth + powiadomienia
    private val basePerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    // Krok 2: lokalizacja w tle (Android wymaga osobnego pytania, po kroku 1)
    private val bgPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    private val associateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Systemowe okno wyboru sparowanego zestawu Bluetooth auta. */
    private fun associateCar() {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()

        cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                associateLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(info: AssociationInfo) {
                val mac = info.deviceMacAddress?.toString() ?: return
                Prefs.saveCar(this@MainActivity, mac, info.displayName?.toString())
                // od teraz system sam budzi CarPresenceService przy połączeniu z autem
                cdm.startObservingDevicePresence(mac)
                refresh++
            }

            override fun onFailure(error: CharSequence?) { refresh++ }
        })
    }

    @Composable
    private fun Screen() {
        val r = refresh   // przebudowa po zmianie stanu
        val trips = remember(r) { TripRepository.list(this) }
        val summary = remember(trips) { TripRepository.summary(trips) }
        val baseOk = has(Manifest.permission.ACCESS_FINE_LOCATION) &&
                has(Manifest.permission.BLUETOOTH_CONNECT)
        val bgOk = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val car = Prefs.carName(this) ?: Prefs.carMac(this)
        val dbOk = remember(r) { SpeedLimitProvider(this).isAvailable }

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
                    ) { associateCar() }
                }
                item {
                    Text(
                        if (dbOk) "✓ Baza ograniczeń prędkości wczytana"
                        else "⚠ Brak bazy limitów – wgraj speedlimits.sqlite do " +
                            "Android/data/pl.drivemonitor/files/ (patrz README)",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                        Text(k); Text("%.1f".format(v))
                    }
                }

                item { HorizontalDivider() }
                item { Text("Trasy", style = MaterialTheme.typography.titleMedium) }
                items(trips) { t -> TripRow(t.gpx, t.stats) }
            }
        }
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
    private fun TripRow(gpx: File, s: org.json.JSONObject?) {
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
                    Text("w trakcie lub przerwana", style = MaterialTheme.typography.bodySmall)
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
