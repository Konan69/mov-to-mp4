package dev.konan.mov2mp4.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

private val MintLight = Color(0xFFA6FFDD)
private val MintDeep = Color(0xFF22DE99)

/**
 * The app icon drawn live, on the launcher icon's 72-unit grid. [spread] moves
 * the two brackets apart a little, which is what the idle animation plays with.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, spread: Float = 0f) {
    val brand = LocalBrand.current
    Canvas(modifier) {
        val u = size.minDimension / 72f
        fun c(v: Float) = (v - 18f) * u
        val corner = CornerRadius(20f * u, 20f * u)

        drawRoundRect(
            brush = Brush.linearGradient(
                0f to brand.iconTop, 0.55f to brand.iconMid, 1f to brand.iconBottom,
                start = Offset.Zero, end = Offset(size.width, size.height),
            ),
            cornerRadius = corner,
        )
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                center = Offset(c(30f), c(22f)), radius = 62f * u,
            ),
            cornerRadius = corner,
        )

        val stroke = Stroke(width = 7.5f * u, cap = StrokeCap.Round)
        val old = Path().apply {
            moveTo(c(30f), c(51f))
            lineTo(c(30f), c(44f))
            arcTo(Rect(c(30f), c(30f), c(58f), c(58f)), 180f, 90f, false)
            lineTo(c(51f), c(30f))
        }
        val new = Path().apply {
            moveTo(c(78f), c(57f))
            lineTo(c(78f), c(64f))
            arcTo(Rect(c(50f), c(50f), c(78f), c(78f)), 0f, 90f, false)
            lineTo(c(57f), c(78f))
        }
        val shift = spread * 2.2f * u
        translate(-shift, -shift) {
            drawPath(old, Color.White.copy(alpha = 0.5f - 0.18f * spread), style = stroke)
        }
        translate(shift, shift) {
            drawPath(
                new,
                Brush.linearGradient(listOf(MintLight, MintDeep), Offset(c(56f), c(56f)), Offset(c(79f), c(79f))),
                style = stroke,
            )
        }
        val play = Path().apply {
            moveTo(c(46.86f), c(43.46f))
            lineTo(c(63.86f), c(54f))
            lineTo(c(46.86f), c(64.54f))
            close()
        }
        drawPath(play, Color.White)
        drawPath(play, Color.White, style = Stroke(width = 4.42f * u, join = StrokeJoin.Round))
    }
}

/** A slow in-and-out value for the idle brand mark. */
@Composable
fun rememberBreath(): Float {
    val transition = rememberInfiniteTransition(label = "breath")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    return value
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "MOV to MP4" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("MOV", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant)
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null,
            tint = LocalBrand.current.mint, modifier = Modifier.size(20.dp),
        )
        Text("MP4", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
    }
}

/** Progress bar that fills from the brand purple into mint. */
@Composable
fun BrandProgress(fraction: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brand = LocalBrand.current
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(240, easing = LinearOutSlowInEasing),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown.coerceAtLeast(0.03f))
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(scheme.primary, brand.mint)))
        )
    }
}

/** Small rounded label, e.g. "Lossless". */
@Composable
fun Pill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}
