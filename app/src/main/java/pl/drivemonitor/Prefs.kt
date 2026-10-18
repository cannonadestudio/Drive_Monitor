package pl.drivemonitor

import android.content.Context

/** Zapamiętany adres MAC zestawu Bluetooth samochodu. */
object Prefs {
    private const val FILE = "drive_monitor"
    private const val KEY_CAR_MAC = "car_mac"
    private const val KEY_CAR_NAME = "car_name"

    fun carMac(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CAR_MAC, null)

    fun carName(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CAR_NAME, null)

    fun saveCar(ctx: Context, mac: String, name: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_CAR_MAC, mac.uppercase())
            .putString(KEY_CAR_NAME, name)
            .apply()
    }

    fun isCar(ctx: Context, mac: String?): Boolean =
        mac != null && mac.equals(carMac(ctx), ignoreCase = true)
}
