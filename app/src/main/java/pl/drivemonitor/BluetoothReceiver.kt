package pl.drivemonitor

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Zapasowy wyzwalacz. ACL_CONNECTED/DISCONNECTED są wyjątkami od zakazu niejawnych
 * broadcastów, więc działają z manifestu. Start usługi z tła jest dozwolony,
 * bo aplikacja ma powiązane urządzenie towarzyszące (CDM).
 * Duplikaty startów nie szkodzą – usługa ignoruje ponowny START.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val device: BluetoothDevice? =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val mac = device?.address
        if (!Prefs.isCar(ctx, mac)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i("BtReceiver", "Połączono z autem $mac")
                runCatching { TripControl.start(ctx) }
                    .onFailure { Log.w("BtReceiver", "Start z tła odrzucony", it) }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i("BtReceiver", "Rozłączono z autem $mac")
                TripControl.stop(ctx)
            }
        }
    }
}
