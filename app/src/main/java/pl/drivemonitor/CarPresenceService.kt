package pl.drivemonitor

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.util.Log

/**
 * System wiąże tę usługę, gdy powiązany zestaw Bluetooth auta połączy się z telefonem
 * (Android 13+ obsługuje tu klasyczny Bluetooth) i odłącza po rozłączeniu.
 * Dzięki powiązaniu CDM aplikacja może wystartować usługę pierwszoplanową z tła.
 */
class CarPresenceService : CompanionDeviceService() {

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "Auto w zasięgu: ${associationInfo.deviceMacAddress}")
        TripControl.start(this)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "Auto poza zasięgiem: ${associationInfo.deviceMacAddress}")
        TripControl.stop(this)
    }

    companion object { private const val TAG = "CarPresence" }
}
