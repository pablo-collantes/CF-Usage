package io.github.pablocollantes.cfusage.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/**
 * A handful of Lucide icons (the set shadcn/ui uses): 24×24, 2 px round strokes. Built from path
 * data so the app needs neither material-icons-extended nor bitmap assets.
 */
object Lucide {
    private fun icon(name: String, vararg paths: String, stroke: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Dashboard by lazy { icon("dashboard", "M4 3h5a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M15 3h5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M15 12h5a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z", "M4 17h5a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1z") }
    val Server by lazy { icon("server", "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z", "M6 6h.01", "M6 18h.01") }
    val Globe by lazy { icon("globe", "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12z", "M12 2a14.5 14.5 0 0 0 0 20a14.5 14.5 0 0 0 0-20", "M2 12h20") }
    val Bell by lazy { icon("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0") }
    val Sliders by lazy { icon("sliders", "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4") }
    val Power by lazy { icon("power", "M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04") }
    val ArrowLeft by lazy { icon("arrow-left", "M12 19l-7-7 7-7", "M19 12H5") }
    val Search by lazy { icon("search", "M19 11A8 8 0 1 1 3 11A8 8 0 1 1 19 11z", "M21 21l-4.3-4.3") }
    val ChevronRight by lazy { icon("chevron-right", "M9 18l6-6-6-6") }
    val ChevronDown by lazy { icon("chevron-down", "M6 9l6 6 6-6") }
    val ChevronUp by lazy { icon("chevron-up", "M18 15l-6-6-6 6") }
    val ArrowUpDown by lazy { icon("arrow-up-down", "M21 16l-4 4-4-4", "M17 20V4", "M3 8l4-4 4 4", "M7 4v16") }
    val Check by lazy { icon("check", "M20 6L9 17l-5-5") }
    val X by lazy { icon("x", "M18 6L6 18", "M6 6l12 12") }
    val Refresh by lazy { icon("refresh", "M3 12a9 9 0 0 1 9-9a9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9a9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }
    val TriangleAlert by lazy { icon("triangle-alert", "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3z", "M12 9v4", "M12 17h.01") }
    val WifiOff by lazy { icon("wifi-off", "M2 2l20 20", "M8.5 16.5a5 5 0 0 1 7 0", "M2 8.82a15 15 0 0 1 4.17-2.65", "M10.66 5c4.01-.36 8.14.9 11.34 3.76", "M16.85 11.25a10 10 0 0 1 2.22 1.68", "M5 13a10 10 0 0 1 5.24-2.76", "M12 20h.01") }
    val Zap by lazy { icon("zap", "M13 2L3 14h9l-1 8l10-12h-9l1-8z") }
    val Shield by lazy { icon("shield", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z") }
    val Lock by lazy { icon("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4") }
    val Undo by lazy { icon("rotate-ccw", "M3 12a9 9 0 1 0 9-9a9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val CircleCheck by lazy { icon("circle-check", "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12z", "M9 12l2 2 4-4") }
    val Info by lazy { icon("info", "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12z", "M12 16v-4", "M12 8h.01") }
    val Database by lazy { icon("database", "M21 5c0 1.66-4.03 3-9 3S3 6.66 3 5s4.03-3 9-3s9 1.34 9 3z", "M3 5v14a9 3 0 0 0 18 0V5", "M3 12a9 3 0 0 0 18 0") }
    val LayoutGrid by lazy { icon("layout-grid", "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z", "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z") }
    val Plus by lazy { icon("plus", "M5 12h14", "M12 5v14") }
    val Pencil by lazy { icon("pencil", "M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z", "M15 5l4 4") }
    val Copy by lazy { icon("copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2") }
    val Trash by lazy { icon("trash-2", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11v6", "M14 11v6") }
    val ArrowUp by lazy { icon("arrow-up", "M5 12l7-7 7 7", "M12 19V5") }
    val ArrowDown by lazy { icon("arrow-down", "M12 5v14", "M19 12l-7 7-7-7") }
    val Layers by lazy { icon("layers", "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z", "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12", "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17") }
    val HomeAdd by lazy { icon("house-plus", "M13.22 2.42a2 2 0 0 0-2.44 0l-7 5.6A2 2 0 0 0 3 9.57V19a2 2 0 0 0 2 2h7", "M21 12V9.57a2 2 0 0 0-.78-1.57l-7-5.6", "M18 15v6", "M15 18h6") }
    val Activity by lazy { icon("activity", "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2") }
}
