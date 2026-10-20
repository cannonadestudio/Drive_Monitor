package pl.drivemonitor

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Usługa pierwszoplanowa: zbiera GPS co 1 s, dopasowuje limit prędkości,
 * zapisuje GPX i statystyki. Kończy się po rozłączeniu Bluetooth auta.
 *
 * Cała praca (GPS, zapytania do bazy limitów, zapis pliku) odbywa się na jednym
 * wątku tła, więc nie ma wyścigów między zapisem punktu a zamknięciem trasy.
 *
 * Krótkie rozłączenia (np. restart radia przy odpalaniu silnika) są tolerowane:
 * STOP uzbraja licznik STOP_GRACE_MS, a ponowny START go anuluje.
 */
class TripRecorderService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var limits: SpeedLimitProvider
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var altitudeConverter: AltitudeConverter? = null

    private var gpx: GpxWriter? = null
    private var lastWritten: TrackPoint? = null
    private var stats = TripStats()
    private val stopRunnable = Runnable { finishTrip() }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("trip-recorder").apply { start() }
        handler = Handler(worker.looper)
        fused = LocationServices.getFusedLocationProviderClient(this)
        limits = SpeedLimitProvider(this)
        Performance.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            altitudeConverter = AltitudeConverter()
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground("Nagrywanie jazdy…")   // musi paść od razu, na wątku głównym
                handler.post {
                    handler.removeCallbacks(stopRunnable)
                    if (gpx == null) beginTrip()
                }
            }
            ACTION_STOP -> handler.post {
                if (gpx == null) { stopSelf(); return@post }
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, STOP_GRACE_MS)
            }
        }
        return START_NOT_STICKY
    }

    private fun beginTrip() {
        val dir = TripRepository.dir(this)
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "$name.gpx")
        gpx = GpxWriter(file)
        currentFile = file
        LiveTrip.resetTrack()
        stats = TripStats()
        publishLive(null, gpsOk = false)

        // jak najczęstsze odczyty (zwykle 1 Hz, na części telefonów więcej) – dla dokładności pomiarów 0–100
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(200L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, worker.looper)
        } catch (e: SecurityException) {
            Log.e(TAG, "Brak uprawnień do lokalizacji", e)
            finishTrip()
            return
        }
        Log.i(TAG, "Start trasy $name")
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private fun onLocation(loc: Location) {
        if (gpx == null) return
        if (loc.accuracy > MAX_ACCURACY_M) { publishLive(null, gpsOk = false); return }
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val bearing = if (loc.hasBearing() && speedKmh > 5) loc.bearing else null
        val limit = stableLimit(limits.lookup(loc.latitude, loc.longitude, bearing), speedKmh, loc.time)

        val p = TrackPoint(
            timeMs = loc.time,
            lat = loc.latitude, lon = loc.longitude,
            altitude = altitudeMsl(loc),
            speedKmh = speedKmh,
            accuracyM = loc.accuracy,
            limitKmh = limit?.kmh,
            limitSource = limit?.source,
        )
        // postój: drgania GPS w miejscu nie trafiają do pliku (inaczej rysują „gwiazdki” na mapie)
        val last = lastWritten
        val standing = last != null && speedKmh < 3 &&
            DisplayTrack.distM(last.lat, last.lon, p.lat, p.lon) < 10 && p.timeMs - last.timeMs < 30_000
        if (!standing) {
            gpx?.add(p)
            lastWritten = p
        }
        stats.add(p)
        updateNotification(p)
        publishLive(p, gpsOk = true)
        LiveTrip.appendTrack(p.lat, p.lon, TripRepository.category(p.speedKmh, p.limitKmh))
        Performance.onFix(p.timeMs, p.speedKmh, p.lat, p.lon)
    }

    // ---------- stabilizacja ograniczenia prędkości ----------
    // Pojedynczy punkt GPS potrafi „trafić” w drogę poprzeczną na skrzyżowaniu albo chwilowo
    // nie mieć danych. Nowy limit przyjmujemy dopiero po 3 kolejnych zgodnych odczytach,
    // na postoju go nie zmieniamy, a przy braku danych trzymamy ostatni przez 15 s.
    private var curLimit: SpeedLimitProvider.Limit? = null
    private var curLimitSeenMs = 0L
    private var candLimit: SpeedLimitProvider.Limit? = null
    private var candCount = 0

    private fun stableLimit(raw: SpeedLimitProvider.Limit?, speedKmh: Double, now: Long): SpeedLimitProvider.Limit? {
        val cur = curLimit
        when {
            raw == null -> {
                if (cur != null && now - curLimitSeenMs > 15_000) curLimit = null
            }
            // Twoja poprawka z przycisku „Popraw” – od razu, bez czekania na potwierdzenie
            cur == null || raw.kmh == cur.kmh || (raw.source == "user" && raw.wayId == cur.wayId) -> {
                curLimit = raw; curLimitSeenMs = now; candLimit = null; candCount = 0
            }
            speedKmh < 5 -> curLimitSeenMs = now          // postój – nie zmieniamy
            else -> {
                if (candLimit?.kmh == raw.kmh) candCount++ else { candLimit = raw; candCount = 1 }
                if (candCount >= 3) { curLimit = raw; curLimitSeenMs = now; candLimit = null; candCount = 0 }
            }
        }
        return curLimit
    }

    private fun publishLive(p: TrackPoint?, gpsOk: Boolean) {
        val prev = LiveTrip.state.value
        LiveTrip.update(
            LiveTrip.State(
                startMs = if (stats.startMs > 0) stats.startMs else prev?.startMs ?: System.currentTimeMillis(),
                speedKmh = p?.speedKmh ?: prev?.speedKmh ?: 0.0,
                limitKmh = if (p != null) p.limitKmh else prev?.limitKmh,
                distanceM = stats.distanceM,
                okM = stats.okDistM,
                overM = stats.overDistM[0],
                altitudeM = p?.altitude ?: prev?.altitudeM,
                gpsOk = gpsOk,
                limitIsDefault = p?.limitSource?.startsWith("default") ?: false,
                maxSpeedKmh = stats.maxSpeedKmh,
                harshBrakes = stats.harshBrakes,
                noLimitM = (stats.distanceM - stats.distWithLimitM).coerceAtLeast(0.0),
                lat = p?.lat ?: prev?.lat ?: 0.0,
                lon = p?.lon ?: prev?.lon ?: 0.0,
                wayId = if (p != null) curLimit?.wayId else prev?.wayId,
                limitIsUser = p?.limitSource == "user",
                fixTimeMs = p?.timeMs ?: prev?.fixTimeMs ?: 0L,
            )
        )
    }

    /**
     * GPS podaje wysokość nad elipsoidą WGS84 – w Polsce to ok. 30–40 m więcej niż n.p.m.
     * Android 14+ ma wbudowany model geoidy, który zamienia ją na wysokość n.p.m.
     * (konwersja może czytać z dysku, dlatego działamy na wątku tła).
     */
    private fun altitudeMsl(loc: Location): Double? {
        if (!loc.hasAltitude()) return null
        val conv = altitudeConverter ?: return loc.altitude
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return loc.altitude
        return runCatching {
            conv.addMslAltitudeToLocation(this, loc)
            if (loc.hasMslAltitude()) loc.mslAltitudeMeters else loc.altitude
        }.getOrDefault(loc.altitude)
    }

    private fun finishTrip() {
        fused.removeLocationUpdates(callback)
        gpx?.let { w ->
            w.close()
            if (stats.distanceM < MIN_TRIP_M) {
                // np. tylko podłączenie telefonu na parkingu – nie zaśmiecamy historii
                w.file.delete()
            } else {
                TripRepository.saveStats(w.file, stats)
            }
            Log.i(TAG, "Koniec trasy: %.1f km".format(stats.distanceM / 1000))
        }
        gpx = null
        lastWritten = null
        curLimit = null; candLimit = null; candCount = 0
        currentFile = null
        LiveTrip.update(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        // domykamy trasę na wątku roboczym, potem zamykamy wątek
        handler.post { if (gpx != null) finishTrip() }
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- powiadomienie ---

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Nagrywanie jazdy", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("Drive Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground(text: String) {
        startForeground(NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun updateNotification(p: TrackPoint) {
        val limitTxt = p.limitKmh?.let { " / limit $it" } ?: ""
        val eleTxt = p.altitude?.let { " · %.0f m".format(it) } ?: ""
        val txt = "%.0f km/h%s · %.1f km%s".format(p.speedKmh, limitTxt, stats.distanceM / 1000, eleTxt)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(txt))
    }

    companion object {
        /** Plik aktualnie nagrywanej trasy (null = nic nie nagrywamy). */
        @Volatile var currentFile: File? = null
            private set

        const val ACTION_START = "pl.drivemonitor.START"
        const val ACTION_STOP = "pl.drivemonitor.STOP"
        private const val TAG = "TripRecorder"
        private const val CHANNEL = "trip"
        private const val NOTIF_ID = 1
        private const val STOP_GRACE_MS = 60_000L   // 1 min tolerancji na zerwanie BT
        private const val MAX_ACCURACY_M = 25f
        private const val MIN_TRIP_M = 300.0
    }
}
