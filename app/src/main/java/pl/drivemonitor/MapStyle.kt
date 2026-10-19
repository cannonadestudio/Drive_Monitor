package pl.drivemonitor

import android.graphics.Color
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Styl mapy (OpenStreetMap / OpenFreeMap) i warstwy trasy wspólne dla ekranu trasy i podglądów. */
object MapStyle {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    const val ROUTE_SOURCE = "route"
    const val CAR_SOURCE = "car"

    private val catColors = listOf("#9AA0A6", "#2E9E5B", "#E0B400", "#F08A24", "#E0412B", "#8E1B6D")

    fun routeSource(fc: FeatureCollection) = GeoJsonSource(ROUTE_SOURCE, fc)

    fun routeLayers(widthScale: Float = 1f): List<Layer> {
        val color = Expression.match(
            Expression.get("cat"),
            Expression.color(Color.parseColor(catColors[0])),
            *catColors.mapIndexed { i, c -> Expression.stop(i.toString(), Expression.color(Color.parseColor(c))) }.toTypedArray()
        )
        return listOf(
            LineLayer("route-casing", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(Color.WHITE),
                PropertyFactory.lineWidth(9f * widthScale),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
            LineLayer("route-line", ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(5f * widthScale),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }

    fun carSource(lat: Double, lon: Double) = GeoJsonSource(CAR_SOURCE, Feature.fromGeometry(Point.fromLngLat(lon, lat)))

    fun carLayers(): List<Layer> = listOf(
        CircleLayer("car-halo", CAR_SOURCE).withProperties(
            PropertyFactory.circleRadius(17f),
            PropertyFactory.circleColor(Color.parseColor("#1A73E8")),
            PropertyFactory.circleOpacity(0.25f),
        ),
        CircleLayer("car-dot", CAR_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Color.parseColor("#1A73E8")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}
