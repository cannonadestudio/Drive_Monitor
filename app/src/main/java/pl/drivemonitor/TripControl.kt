package pl.drivemonitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Wspólny punkt startu/stopu nagrywania – wołany przez CDM, receiver BT i UI. */
object TripControl {
    fun start(ctx: Context) {
        val i = Intent(ctx, TripRecorderService::class.java).setAction(TripRecorderService.ACTION_START)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(ctx: Context) {
        val i = Intent(ctx, TripRecorderService::class.java).setAction(TripRecorderService.ACTION_STOP)
        // startService jest tu bezpieczne: usługa już działa na pierwszym planie
        runCatching { ctx.startService(i) }
    }
}
