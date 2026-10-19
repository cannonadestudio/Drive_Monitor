package pl.drivemonitor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import kotlin.coroutines.resume

/**
 * Podgląd trasy na prawdziwej mapie (zdjęcie mapy z MapLibre + kolorowa trasa).
 * Bez internetu korzysta z pobranej mapy offline; gdy mapy brak – sam kształt trasy.
 */
@Composable
fun MapPreview(gpx: File, background: Color, gridColor: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val thumb by produceState<List<ThumbPoint>?>(null, gpx.name) {
            value = withContext(Dispatchers.IO) { runCatching { TripRepository.thumbnail(ctx, gpx) }.getOrNull() }
        }
        val bitmap by produceState<Bitmap?>(null, thumb, wPx, hPx) {
            val pts = thumb
            if (pts == null || pts.size < 2 || wPx <= 0 || hPx <= 0) return@produceState
            value = snapshot(ctx, pts, (wPx / density).toInt(), (hPx / density).toInt(), density)
        }
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = "Mapa trasy", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            RouteThumb(thumb, background, gridColor, Modifier.fillMaxSize(), lineWidth = androidx.compose.ui.unit.Dp(4f), padding = androidx.compose.ui.unit.Dp(22f))
        }
    }
}

private suspend fun snapshot(ctx: android.content.Context, pts: List<ThumbPoint>, w: Int, h: Int, density: Float): Bitmap? =
    suspendCancellableCoroutine { cont ->
        MapLibre.getInstance(ctx.applicationContext)
        // odcinki w kolorach kategorii
        val features = ArrayList<Feature>()
        var cur = ArrayList<Point>(); var cat = -1
        for (i in 1 until pts.size) {
            if (pts[i].cat != cat) {
                if (cur.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(cur)).also { it.addStringProperty("cat", cat.toString()) }
                cur = arrayListOf(Point.fromLngLat(pts[i - 1].lon, pts[i - 1].lat)); cat = pts[i].cat
            }
            cur += Point.fromLngLat(pts[i].lon, pts[i].lat)
        }
        if (cur.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(cur)).also { it.addStringProperty("cat", cat.toString()) }

        // obszar trasy z marginesem 15%
        var s = 90.0; var n = -90.0; var we = 180.0; var e = -180.0
        pts.forEach { s = minOf(s, it.lat); n = maxOf(n, it.lat); we = minOf(we, it.lon); e = maxOf(e, it.lon) }
        val mLat = maxOf((n - s) * 0.15, 0.002); val mLon = maxOf((e - we) * 0.15, 0.003)
        val bounds = LatLngBounds.Builder().include(LatLng(s - mLat, we - mLon)).include(LatLng(n + mLat, e + mLon)).build()

        val style = Style.Builder().fromUri(MapStyle.STYLE_URL).withSource(MapStyle.routeSource(FeatureCollection.fromFeatures(features)))
        MapStyle.routeLayers(widthScale = 0.8f).forEach { style.withLayer(it) }

        val snapshotter = MapSnapshotter(
            ctx,
            MapSnapshotter.Options(w, h).withStyleBuilder(style).withRegion(bounds).withPixelRatio(density).withLogo(false)
        )
        cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
        snapshotter.start(
            { snap -> if (cont.isActive) cont.resume(snap.bitmap) },
            { _ -> if (cont.isActive) cont.resume(null) }
        )
    }
