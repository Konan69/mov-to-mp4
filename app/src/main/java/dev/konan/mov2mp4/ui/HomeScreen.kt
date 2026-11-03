package dev.konan.mov2mp4.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.konan.mov2mp4.data.Converter
import dev.konan.mov2mp4.data.Job
import dev.konan.mov2mp4.data.JobState

/** Offer to turn notifications on; null when there is nothing to ask. */
class NotifyOffer(val accept: () -> Unit, val dismiss: () -> Unit)

/** Files this big take long enough that people leave the app while they convert. */
private const val LONG_JOB_BYTES = 150L * 1000 * 1000

@Composable
fun HomeScreen(
    jobs: List<Job>,
    onPick: () -> Unit,
    onOpen: (Job) -> Unit,
    onShare: (List<Job>) -> Unit,
    notifyOffer: NotifyOffer? = null,
) {
    var detailsFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var about by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 22.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark(Modifier.weight(1f))
                IconButton(onClick = { about = true }) {
                    Icon(Icons.Rounded.Info, contentDescription = "How it works", tint = scheme.onSurfaceVariant)
                }
            }
        },
        floatingActionButton = {
            if (jobs.isNotEmpty()) {
                // With a long list the wide button would sit on top of a card, so it
                // shrinks to a round "+".
                ExtendedFloatingActionButton(
                    onClick = onPick,
                    expanded = jobs.size <= 2,
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = "Add videos") },
                    text = { Text("Add videos") },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = jobs.isEmpty(),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
            label = "home",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { empty ->
            if (empty) Welcome(onPick)
            else Queue(jobs, onOpen, onShare, onDetails = { detailsFor = it.id }, notifyOffer = notifyOffer)
        }
    }

    jobs.firstOrNull { it.id == detailsFor }?.let { job ->
        DetailsSheet(job, onDismiss = { detailsFor = null })
    }
    if (about) AboutSheet(onDismiss = { about = false })
}

@Composable
private fun Welcome(onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val brand = LocalBrand.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Soft coloured light behind the mark.
            Box(
                Modifier
                    .size(260.dp)
                    .background(
                        Brush.radialGradient(
                            0f to brand.iconMid.copy(alpha = 0.30f),
                            0.45f to brand.iconMid.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                        ),
                        CircleShape,
                    )
            )
            BrandMark(Modifier.size(116.dp), spread = rememberBreath())
        }
        Spacer(Modifier.height(30.dp))
        Text(
            "Same video, new wrapper",
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Turns a MOV into an MP4 without re-encoding it. Every frame and every bit of sound stays exactly as it was.",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        Spacer(Modifier.height(26.dp))
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(scheme.surfaceContainer)
                .border(1.dp, brand.hairline, MaterialTheme.shapes.large)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Promise("No quality loss, and it takes seconds")
            Promise("Keeps HDR, Dolby Vision, date and location")
            Promise("Works offline. Nothing leaves your phone")
        }
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onPick,
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .height(56.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose videos", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Or open a .mov in WhatsApp, Files or any other app and pick MOV to MP4.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        // Sits the group a little above centre, where it reads as centred.
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun Promise(text: String) {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(brand.mintContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = brand.onMintContainer, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Queue(
    jobs: List<Job>,
    onOpen: (Job) -> Unit,
    onShare: (List<Job>) -> Unit,
    onDetails: (Job) -> Unit,
    notifyOffer: NotifyOffer?,
) {
    val scheme = MaterialTheme.colorScheme
    val done = jobs.filter { it.state is JobState.Done }
    val active = jobs.count { it.isActive }
    val longJob = jobs.any { it.isActive && it.size >= LONG_JOB_BYTES }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp)
                    .animateItem(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    summary(jobs.size, done.size, active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (done.size > 1) {
                    TextButton(onClick = { onShare(done) }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share all")
                    }
                }
                if (jobs.any { !it.isActive } && jobs.size > 1) {
                    TextButton(onClick = { Converter.clearFinished() }) { Text("Clear") }
                }
            }
        }
        if (notifyOffer != null && longJob) {
            item(key = "notify") { NotifyCard(notifyOffer, Modifier.animateItem()) }
        }
        items(jobs, key = { it.id }) { job ->
            JobCard(
                job = job,
                onDetails = { onDetails(job) },
                onOpen = { onOpen(job) },
                onShare = { onShare(listOf(job)) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun NotifyCard(offer: NotifyOffer, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(scheme.primaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Notifications, contentDescription = null,
                tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Want to know when it's done?",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Big videos keep converting if you leave the app. A notification can tell you when they finish.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = offer.dismiss) { Text("Not now", color = scheme.onPrimaryContainer) }
            TextButton(onClick = offer.accept) { Text("Notify me", color = scheme.onPrimaryContainer) }
        }
    }
}

private fun summary(total: Int, done: Int, active: Int): String = when {
    active > 0 && total == 1 -> "Converting"
    active > 0 -> "$done of $total done"
    done == total && total == 1 -> "Done"
    done == total -> "All $total done"
    total == 1 -> "Not converted"
    else -> "$done of $total converted"
}
