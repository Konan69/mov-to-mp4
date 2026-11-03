package dev.konan.mov2mp4.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.konan.mov2mp4.data.Converter
import dev.konan.mov2mp4.data.Job
import dev.konan.mov2mp4.data.JobState
import dev.konan.mov2mp4.engine.Labels
import dev.konan.mov2mp4.engine.Remux
import kotlin.math.roundToInt

@Composable
fun JobCard(
    job: Job,
    onDetails: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onDetails,
        enabled = job.info != null,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, LocalBrand.current.hairline),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(job)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { if (job.isActive) Converter.cancel(job.id) else Converter.remove(job.id) },
                    modifier = Modifier
                        .offset(x = 8.dp)
                        .size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = if (job.isActive) "Cancel" else "Remove from list",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = job.state,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> spring(stiffness = 500f) })
                },
                label = "state",
            ) { state ->
                when (state) {
                    JobState.Preparing -> Waiting("Reading the file")
                    JobState.Queued -> Waiting("Waiting for its turn")
                    is JobState.Converting -> Converting(state)
                    is JobState.Done -> Done(state, onOpen, onShare)
                    is JobState.Failed -> Failed(job, state)
                    JobState.Cancelled -> Cancelled(job)
                }
            }
        }
    }
}

private fun subtitle(job: Job): String {
    val info = job.info
    val parts = mutableListOf<String>()
    if (info != null) Labels.summary(info).takeIf { it.isNotEmpty() }?.let(parts::add)
    Labels.size(job.size).takeIf { it.isNotEmpty() }?.let(parts::add)
    return if (parts.isEmpty()) "MOV video" else parts.joinToString("  ·  ")
}

@Composable
private fun Thumbnail(job: Job) {
    val scheme = MaterialTheme.colorScheme
    val brand = LocalBrand.current
    Box(Modifier.size(60.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(brand.iconTop, brand.iconBottom))),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = job.thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        val badge: Pair<ImageVector, Color>? = when (job.state) {
            is JobState.Done -> Icons.Rounded.Check to brand.mint
            is JobState.Failed -> Icons.Rounded.Close to scheme.error
            else -> null
        }
        AnimatedVisibility(
            visible = badge != null,
            enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 5.dp, y = 5.dp),
        ) {
            val (icon, tint) = badge ?: (Icons.Rounded.Check to brand.mint)
            Box(
                Modifier
                    .size(22.dp)
                    .border(2.dp, scheme.surfaceContainer, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = scheme.surfaceContainer, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Converting(state: JobState.Converting) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        BrandProgress(state.fraction)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.phase) {
                    Remux.PHASE_COPY -> "Copying picture and sound as they are"
                    Remux.PHASE_FINISH -> "Finishing the MP4"
                    else -> "Checking the copy against the original"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(state.fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurface,
            )
        }
    }
}

@Composable
private fun Done(state: JobState.Done, onOpen: () -> Unit, onShare: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val brand = LocalBrand.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Lossless", brand.mintContainer, brand.onMintContainer, icon = Icons.Rounded.Check)
            if (state.caution != null) {
                Spacer(Modifier.width(6.dp))
                Pill(state.caution, brand.amberContainer, brand.onAmberContainer, icon = Icons.Rounded.Warning)
            } else if (state.notes.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Pill(
                    if (state.notes.size == 1) "1 note" else "${state.notes.size} notes",
                    scheme.surfaceContainerHighest,
                    scheme.onSurfaceVariant,
                    icon = Icons.Rounded.Info,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                listOf(Labels.size(state.outputSize), Labels.elapsed(state.elapsedMs))
                    .filter { it.isNotEmpty() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Saved to Movies › ${Converter.OUTPUT_FOLDER}",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            FilledTonalButton(
                onClick = onOpen,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = scheme.surfaceContainerHighest,
                    contentColor = scheme.onSurface,
                ),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
        }
    }
}

@Composable
private fun Failed(job: Job, state: JobState.Failed) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(state.title, style = MaterialTheme.typography.titleSmall, color = scheme.error)
        }
        Spacer(Modifier.height(6.dp))
        Text(state.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        if (state.canDropAudio || state.canRetry) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { Converter.retry(job.id, withoutAudio = state.canDropAudio) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = scheme.surfaceContainerHighest,
                    contentColor = scheme.onSurface,
                ),
            ) {
                Text(if (state.canDropAudio) "Convert without sound" else "Try again")
            }
        }
    }
}

@Composable
private fun Cancelled(job: Job) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Cancelled. Nothing was saved.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = { Converter.retry(job.id) },
            modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = scheme.surfaceContainerHighest,
                contentColor = scheme.onSurface,
            ),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try again")
        }
    }
}
