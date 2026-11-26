package com.mohit.dimplex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class Series(val name: String, val color: Color, val points: List<Pair<Long, Double>>, val stepped: Boolean = false)

/** Tiny Canvas line chart: shared x (time) range, auto y range. No external chart lib needed. */
@Composable
fun LineChart(title: String, series: List<Series>, from: Long, to: Long, unit: String) {
    val all = series.flatMap { it.points }.map { it.second }
    val lo = all.minOrNull(); val hi = all.maxOrNull()
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Text(
            "$title  " + (if (lo != null) "(%.1f – %.1f %s)".format(lo, hi, unit) else "(no data)") +
                "   " + series.joinToString("  ") { "■ ${it.name}" },
            style = MaterialTheme.typography.labelLarge,
        )
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            for (i in 0..4) {
                val y = size.height * i / 4
                drawLine(grid, Offset(0f, y), Offset(size.width, y))
            }
            if (lo == null || hi == null) return@Canvas
            val pad = ((hi - lo) * 0.1).coerceAtLeast(0.5)
            val ymin = lo - pad; val ymax = hi + pad
            val span = (to - from).coerceAtLeast(1)
            fun x(t: Long) = ((t - from).toFloat() / span) * size.width
            fun y(v: Double) = (size.height * (1 - (v - ymin) / (ymax - ymin))).toFloat()
            for (s in series) {
                if (s.points.isEmpty()) continue
                val p = Path()
                s.points.forEachIndexed { i, (t, v) ->
                    if (i == 0) p.moveTo(x(t), y(v))
                    else { if (s.stepped) p.lineTo(x(t), y(s.points[i - 1].second)); p.lineTo(x(t), y(v)) }
                }
                drawPath(p, s.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}
