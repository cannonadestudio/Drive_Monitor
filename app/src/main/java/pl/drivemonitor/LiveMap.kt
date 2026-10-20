package pl.drivemonitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * Żywa mapa trwającej jazdy (ekran główny): trasa rysuje się na bieżąco w kolorach przekroczeń,
 * auto w środku, mapa obrócona w kierunku jazdy. Bez gestów – dotyk obsługuje karta nad mapą.
 */
@Composable
fun LiveMap(track: List<ThumbPoint>, live: LiveTrip.State?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        MapView(ctx).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var routeSrc by remember { mutableStateOf<GeoJsonSource?>(null) }
    var carSrc by remember { mutableStateOf<GeoJsonSource?>(null) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        // addObserver sam „dogania” bieżący stan (ON_START/ON_RESUME), gdy karta pojawi się w trakcie
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromUri(MapStyle.STYLE_URL)) { style ->
                val r = MapStyle.routeSource(MapStyle.featuresFromThumb(track))
                style.addSource(r)
                MapStyle.routeLayers(widthScale = 0.8f).forEach { style.addLayer(it) }
                val p = track.lastOrNull()
                val c = MapStyle.carSource(p?.lat ?: live?.lat ?: 0.0, p?.lon ?: live?.lon ?: 0.0)
                style.addSource(c)
                MapStyle.carLayers().forEach { style.addLayer(it) }
                routeSrc = r; carSrc = c
            }
            map = m
        }
    }

    // nowa część trasy
    LaunchedEffect(track.size, routeSrc) {
        routeSrc?.setGeoJson(MapStyle.featuresFromThumb(track))
    }

    // auto i kamera: co odczyt GPS
    var lastBearing by remember { mutableStateOf(0.0) }
    LaunchedEffect(live?.fixTimeMs, map, carSrc) {
        val m = map ?: return@LaunchedEffect
        val lat = live?.lat ?: track.lastOrNull()?.lat ?: return@LaunchedEffect
        val lon = live?.lon ?: track.lastOrNull()?.lon ?: return@LaunchedEffect
        if (lat == 0.0 && lon == 0.0) return@LaunchedEffect
        carSrc?.setGeoJson(Point.fromLngLat(lon, lat))
        // kierunek z dwóch ostatnich punktów śladu
        if (track.size >= 2) {
            val a = track[track.size - 2]; val b = track.last()
            if (DisplayTrack.distM(a.lat, a.lon, b.lat, b.lon) > 5) {
                val k = Math.cos(Math.toRadians(a.lat))
                lastBearing = (Math.toDegrees(Math.atan2((b.lon - a.lon) * k, b.lat - a.lat)) + 360) % 360
            }
        }
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(lat, lon)).zoom(15.0).tilt(40.0).bearing(lastBearing).build()
            ),
            900
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
