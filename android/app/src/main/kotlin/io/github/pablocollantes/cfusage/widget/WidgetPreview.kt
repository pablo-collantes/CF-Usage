package io.github.pablocollantes.cfusage.widget

import android.util.Log
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.pablocollantes.cfusage.data.Summary
import io.github.pablocollantes.cfusage.ui.components.CardShape

/**
 * The real widget, inside the app: the design is composed by Glance into RemoteViews exactly as
 * the launcher gets it, then inflated here. What you see in the editor is what the home screen shows.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
fun WidgetPreview(design: WidgetDesign, summary: Summary?, widthDp: Float, heightDp: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val size = DpSize(widthDp.dp, heightDp.dp)
    val views by produceState<RemoteViews?>(null, design, summary, widthDp, heightDp, dark) {
        value = runCatching {
            GlanceRemoteViews().compose(context, size) { DesignContent(design, summary, interactive = false) }.remoteViews
        }.onFailure { Log.w("CfUsage", "widget preview failed", it) }.getOrNull()
    }
    // A fresh view per composition result, built with its content: a view filled in later isn't
    // redrawn reliably under the scaling graphicsLayer, which left some list previews blank.
    val rv = views
    if (rv == null) {
        Box(modifier.size(size))
        return
    }
    key(rv) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    runCatching {
                        addView(rv.apply(ctx, this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }.onFailure { Log.w("CfUsage", "widget preview inflate failed", it) }
                }
            },
            modifier = modifier.size(size),
        )
    }
}

/** A wallpaper-like backdrop, so background opacity and borders can be judged. */
@Composable
fun wallpaperBrush(): Brush = if (isSystemInDarkTheme()) {
    Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0F766E), Color(0xFF14532D)))
} else {
    Brush.linearGradient(listOf(Color(0xFFC7D2FE), Color(0xFF99F6E4), Color(0xFFFDE68A)))
}

/**
 * The preview scaled down to fit [maxHeight] and the available width, centred on the backdrop.
 * Scaling keeps the widget's own layout (it is laid out at its real dp size, then drawn smaller).
 */
@Composable
fun WidgetStage(
    design: WidgetDesign,
    summary: Summary?,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
    pad: Dp = 16.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availW = maxWidth - pad * 2
        val k = minOf(1f, availW / widthDp.dp, (maxHeight - pad * 2) / heightDp.dp)
        Box(
            Modifier.fillMaxWidth().height(heightDp.dp * k + pad * 2).clip(CardShape).background(wallpaperBrush()),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(widthDp.dp * k, heightDp.dp * k)) {
                WidgetPreview(
                    design,
                    summary,
                    widthDp,
                    heightDp,
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(widthDp.dp, heightDp.dp)
                        .graphicsLayer {
                            scaleX = k
                            scaleY = k
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                )
            }
        }
    }
}
