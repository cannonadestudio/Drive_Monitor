package pl.drivemonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Uproszczony punkt trasy do miniatury: kategoria prędkości jak w [SpeedColors]. */
data class ThumbPoint(val lon: Double, val lat: Double, val cat: Int)

/**
 * Podgląd trasy rysowany natywnie (bez internetu): kolorowa linia przekroczeń
 * na delikatnej siatce, start (zielony) i koniec (ciemny).
 */
@Composable
fun RouteThumb(
    points: List<ThumbPoint>?,
    background: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 3.dp,
    padding: Dp = 12.dp,
) {
    Box(modifier.background(background)) {
        if (points == null || points.size < 2) return@Box
        Canvas(Modifier.matchParentSize()) {
            val step = 18.dp.toPx()
            var gx = 0f
            while (gx < size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 1f); gx += step }
            var gy = 0f
            while (gy < size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1f); gy += step }

            // Mercator dopasowany do pola z zachowaniem proporcji
            fun mx(lon: Double) = lon * PI / 180
            fun my(lat: Double) = ln(tan(PI / 4 + lat * PI / 360))
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE
            var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (p in points) {
                val x = mx(p.lon); val y = my(p.lat)
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            val pad = padding.toPx()
            val w = size.width - 2 * pad; val h = size.height - 2 * pad
            val s = min(w / max(x1 - x0, 1e-9), h / max(y1 - y0, 1e-9))
            val ox = pad + (w - (x1 - x0) * s) / 2
            val oy = pad + (h - (y1 - y0) * s) / 2
            fun pt(p: ThumbPoint) = Offset((ox + (mx(p.lon) - x0) * s).toFloat(), (oy + (y1 - my(p.lat)) * s).toFloat())

            val lw = lineWidth.toPx()
            for (i in 1 until points.size) {
                drawLine(Color.White, pt(points[i - 1]), pt(points[i]), lw + 3.dp.toPx(), StrokeCap.Round)
            }
            for (i in 1 until points.size) {
                drawLine(SpeedColors[points[i].cat.coerceIn(0, 5)], pt(points[i - 1]), pt(points[i]), lw, StrokeCap.Round)
            }
            val r = lw * 1.4f
            drawCircle(Color(0xFF2E9E5B), r, pt(points.first()))
            drawCircle(Color.White, r, pt(points.first()), style = Stroke(lw * 0.6f))
            drawCircle(Color(0xFF1D2125), r, pt(points.last()))
            drawCircle(Color.White, r, pt(points.last()), style = Stroke(lw * 0.6f))
        }
    }
}
