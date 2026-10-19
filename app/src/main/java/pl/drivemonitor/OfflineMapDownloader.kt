package pl.drivemonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

/**
 * Mapa offline przez mechanizm wbudowany w MapLibre (regiony offline):
 * styl, ikony, czcionki i kafelki do zbliżenia 14 dla wybranego prostokąta.
 * Pobrane regiony są automatycznie używane przez ekran trasy i podglądy.
 * Wszystkie wywołania – z wątku głównego.
 */
object OfflineMapDownloader {

    data class Progress(val done: Long, val total: Long, val running: Boolean, val message: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private var active: OfflineRegion? = null

    private fun manager(ctx: Context): OfflineManager {
        MapLibre.getInstance(ctx.applicationContext)
        return OfflineManager.getInstance(ctx.applicationContext)
    }

    /** bbox: [south, west, north, east] */
    fun start(ctx: Context, bbox: DoubleArray, label: String) {
        if (_progress.value?.running == true) return
        val bounds = LatLngBounds.Builder()
            .include(LatLng(bbox[0], bbox[1]))
            .include(LatLng(bbox[2], bbox[3]))
            .build()
        val density = ctx.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(MapStyle.STYLE_URL, bounds, 0.0, 14.0, density)
        _progress.value = Progress(0, 0, true, "Przygotowanie: $label")

        manager(ctx).createOfflineRegion(definition, label.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                active = offlineRegion
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val mb = status.completedResourceSize / 1_048_576.0
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            active = null
                            _progress.value = Progress(
                                status.completedResourceCount, status.requiredResourceCount, false,
                                "Gotowe: $label (%.0f MB). Ta okolica działa teraz bez internetu.".format(mb)
                            )
                        } else {
                            _progress.value = Progress(
                                status.completedResourceCount, status.requiredResourceCount, true,
                                "Pobieranie: $label – %.0f MB".format(mb)
                            )
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        // błędy pojedynczych plików – MapLibre ponawia sam; pokazujemy tylko informację
                        val p = _progress.value
                        _progress.value = (p ?: Progress(0, 0, true, "")).copy(message = "Problem z siecią (${error.reason}) – ponawiam…")
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        active = null
                        _progress.value = Progress(0, 0, false, "Obszar za duży – wybierz mniejszy.")
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                _progress.value = Progress(0, 0, false, "Nie udało się rozpocząć pobierania: $error")
            }
        })
    }

    fun cancel() {
        active?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        active = null
        val p = _progress.value
        _progress.value = Progress(p?.done ?: 0, p?.total ?: 0, false, "Przerwano. Pobrane fragmenty zostają w telefonie.")
    }

    /** Liczba pobranych okolic. */
    fun countRegions(ctx: Context, onResult: (Int) -> Unit) {
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) = onResult(offlineRegions?.size ?: 0)
            override fun onError(error: String) = onResult(0)
        })
    }

    /** Usuwa wszystkie pobrane okolice. */
    fun deleteAll(ctx: Context, onDone: () -> Unit) {
        cancel()
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                if (list.isEmpty()) { onDone(); return }
                var left = list.size
                list.forEach { r ->
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() { if (--left == 0) onDone() }
                        override fun onError(error: String) { if (--left == 0) onDone() }
                    })
                }
            }
            override fun onError(error: String) = onDone()
        })
        _progress.value = null
    }

    /** Prostokąt wokół punktu o promieniu w km. */
    fun bboxAround(lat: Double, lon: Double, km: Double): DoubleArray {
        val dLat = km / 111.32
        val dLon = km / (111.32 * cos(Math.toRadians(lat)))
        return doubleArrayOf(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }
}
