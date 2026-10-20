package pl.drivemonitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Przeciążenia g z czujników telefonu (grawitacja + przyspieszenie liniowe).
 * Telefon może leżeć w uchwycie dowolnie: kierunek „do przodu” auta jest uczony automatycznie –
 * porównujemy wskazania czujnika z przyspieszaniem/hamowaniem widocznym w prędkości GPS.
 * Wyuczony kierunek zapamiętujemy (uchwyt zwykle stoi tak samo).
 *
 *  gLong: + przyspieszanie, − hamowanie;  gLat: + zakręt w prawo, − w lewo (siła odśrodkowa odwrotnie)
 */
class GForce(private val ctx: Context) : SensorEventListener {

    data class G(
        val gLong: Double = 0.0, val gLat: Double = 0.0, val total: Double = 0.0,
        val maxBrake: Double = 0.0, val maxAccel: Double = 0.0, val maxLeft: Double = 0.0, val maxRight: Double = 0.0,
        val calibrated: Boolean = false, val learnSamples: Int = 0,
    )

    private val _g = MutableStateFlow(G())
    val g: StateFlow<G> = _g

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val prefs = ctx.getSharedPreferences("gforce", Context.MODE_PRIVATE)

    private val up = DoubleArray(3)              // jednostkowy wektor „w górę” w układzie telefonu
    private val lin = DoubleArray(3)             // przyspieszenie liniowe (wygładzone), m/s²
    private val linSlow = DoubleArray(3)         // wolniej wygładzone – do uczenia kierunku
    private var fwd: DoubleArray? = null         // kierunek jazdy w układzie telefonu
    private var samples = prefs.getInt("n", 0)
    private var hasGravity = false

    init {
        if (prefs.contains("fx")) fwd = doubleArrayOf(
            prefs.getFloat("fx", 0f).toDouble(), prefs.getFloat("fy", 0f).toDouble(), prefs.getFloat("fz", 0f).toDouble()
        )
        _g.value = _g.value.copy(calibrated = samples >= MIN_SAMPLES && fwd != null, learnSamples = samples)
    }

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    val available get() = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null && sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null

    fun resetPeaks() {
        _g.value = _g.value.copy(maxBrake = 0.0, maxAccel = 0.0, maxLeft = 0.0, maxRight = 0.0)
    }

    fun recalibrate() {
        fwd = null; samples = 0
        prefs.edit().clear().apply()
        _g.value = _g.value.copy(calibrated = false, learnSamples = 0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val n = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                if (n > 1) { for (i in 0..2) up[i] = e.values[i] / n; hasGravity = true }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                for (i in 0..2) {
                    lin[i] += (e.values[i] - lin[i]) * 0.15        // ~0,3 s
                    linSlow[i] += (e.values[i] - linSlow[i]) * 0.04 // ~1 s
                }
                update()
            }
        }
    }

    private fun horizontal(v: DoubleArray): DoubleArray {
        val d = v[0] * up[0] + v[1] * up[1] + v[2] * up[2]
        return doubleArrayOf(v[0] - d * up[0], v[1] - d * up[1], v[2] - d * up[2])
    }

    private fun update() {
        if (!hasGravity) return
        val h = horizontal(lin)
        val f = fwd
        val cur = _g.value
        if (f == null || samples < MIN_SAMPLES) {
            val total = sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]) / G0
            _g.value = cur.copy(total = total, gLong = 0.0, gLat = 0.0, calibrated = false)
            return
        }
        val fh = norm(horizontal(f)) ?: return
        // prawo = przód × góra
        val right = doubleArrayOf(
            fh[1] * up[2] - fh[2] * up[1],
            fh[2] * up[0] - fh[0] * up[2],
            fh[0] * up[1] - fh[1] * up[0],
        )
        val gLong = (h[0] * fh[0] + h[1] * fh[1] + h[2] * fh[2]) / G0
        val gLat = (h[0] * right[0] + h[1] * right[1] + h[2] * right[2]) / G0
        _g.value = cur.copy(
            gLong = gLong, gLat = gLat, total = sqrt(gLong * gLong + gLat * gLat), calibrated = true,
            maxBrake = maxOf(cur.maxBrake, -gLong), maxAccel = maxOf(cur.maxAccel, gLong),
            maxLeft = maxOf(cur.maxLeft, -gLat), maxRight = maxOf(cur.maxRight, gLat),
        )
    }

    /**
     * Uczenie kierunku jazdy: woła ekran przy każdej zmianie prędkości z GPS.
     * Gdy GPS pokazuje wyraźne przyspieszanie/hamowanie, poziome przyspieszenie z czujnika
     * wskazuje (ze znakiem) kierunek przodu auta.
     */
    fun onGpsAccel(accelMs2: Double, speedKmh: Double) {
        if (!hasGravity || speedKmh < 8 || abs(accelMs2) < 0.8) return
        val h = norm(horizontal(linSlow)) ?: return
        val s = if (accelMs2 > 0) 1.0 else -1.0
        val sample = doubleArrayOf(h[0] * s, h[1] * s, h[2] * s)
        val f = fwd
        fwd = if (f == null) sample else norm(doubleArrayOf(f[0] * 0.85 + sample[0] * 0.15, f[1] * 0.85 + sample[1] * 0.15, f[2] * 0.85 + sample[2] * 0.15))
        samples++
        fwd?.let { v -> prefs.edit().putFloat("fx", v[0].toFloat()).putFloat("fy", v[1].toFloat()).putFloat("fz", v[2].toFloat()).putInt("n", samples).apply() }
        _g.value = _g.value.copy(learnSamples = samples, calibrated = samples >= MIN_SAMPLES)
    }

    private fun norm(v: DoubleArray): DoubleArray? {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (n < 1e-3) null else doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    companion object {
        private const val G0 = 9.80665
        const val MIN_SAMPLES = 6
    }
}
