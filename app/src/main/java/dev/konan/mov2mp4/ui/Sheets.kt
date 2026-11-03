package dev.konan.mov2mp4.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.konan.mov2mp4.BuildConfig
import dev.konan.mov2mp4.data.Converter
import dev.konan.mov2mp4.data.Job
import dev.konan.mov2mp4.data.JobState
import dev.konan.mov2mp4.engine.Labels
import dev.konan.mov2mp4.engine.MediaInfo
import dev.konan.mov2mp4.engine.Remux
import dev.konan.mov2mp4.engine.Track
import dev.konan.mov2mp4.engine.TrackAction
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(job: Job, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(job.name, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
            val info = job.info
            val state = job.state

            if (state is JobState.Done) {
                Section("Result")
                Fact("Saved as", state.outputName)
                Fact("Where", "Movies › ${Converter.OUTPUT_FOLDER}")
                Fact("Size", "${Labels.size(job.size)} → ${Labels.size(state.outputSize)}")
                Fact("Time taken", Labels.elapsed(state.elapsedMs))
                Fact("Checked", "Read back and compared with the MOV: an exact match")
                state.copied.forEach { copied ->
                    val count = NumberFormat.getIntegerInstance().format(copied.packets)
                    when (copied.type) {
                        "video" -> Fact("Picture", "$count frames copied, none re-encoded")
                        "audio" -> Fact("Sound", "$count audio packets copied, none re-encoded")
                        "subtitle" -> Fact("Captions", "$count copied")
                    }
                }
            }
            if (state is JobState.Failed) {
                Section(state.title)
                Text(state.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                if (state.technical.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.technical.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerHighest)
                            .padding(12.dp),
                    )
                }
            }
            val notes = (state as? JobState.Done)?.notes.orEmpty()
            if (notes.isNotEmpty()) {
                Section("Worth knowing")
                notes.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            if (info != null) {
                info.tracks.filter { it.isVideo }.forEachIndexed { i, t -> VideoFacts(t, i, info) }
                info.tracks.filter { it.isAudio }.forEachIndexed { i, t -> AudioFacts(t, i, info) }
                FileFacts(job, info)
            }
        }
    }
}

@Composable
private fun VideoFacts(t: Track, index: Int, info: MediaInfo) {
    val many = info.tracks.count { it.isVideo } > 1
    Section(if (many) "Picture ${index + 1}" else "Picture")
    Fact("Format", listOf(Labels.videoCodec(t), t.profile).filter { it.isNotBlank() }.joinToString(", "))
    if (t.width > 0) {
        val turned = if (t.rotation != 0) ", shown turned ${t.rotation}°" else ""
        Fact("Size", "${t.width} × ${t.height}$turned")
    }
    Labels.fps(t.fps).takeIf { it.isNotEmpty() }?.let { Fact("Frame rate", it) }
    Labels.bitRate(t.bitRate).takeIf { it.isNotEmpty() }?.let { Fact("Bit rate", it) }
    val colour = listOfNotNull(
        Labels.hdr(t),
        if (t.bitDepth > 0) "${t.bitDepth}-bit" else null,
    ).joinToString(", ")
    if (colour.isNotEmpty()) Fact("Colour", colour)
    t.timecode?.let { Fact("Timecode", it) }
    if (t.action != TrackAction.COPY) Fact("In the MP4", "Left out: MP4 can't hold it")
}

@Composable
private fun AudioFacts(t: Track, index: Int, info: MediaInfo) {
    val many = info.tracks.count { it.isAudio } > 1
    Section(if (many) "Sound ${index + 1}" else "Sound")
    Fact("Format", Labels.audioCodec(t))
    val shape = listOf(Labels.channels(t), Labels.sampleRate(t.sampleRate)).filter { it.isNotEmpty() }
    if (shape.isNotEmpty()) Fact("Channels", shape.joinToString(", "))
    Labels.bitRate(t.bitRate).takeIf { it.isNotEmpty() }?.let { Fact("Bit rate", it) }
    t.language?.let { Fact("Language", it) }
    if (t.action != TrackAction.COPY) Fact("In the MP4", "Left out: MP4 can't hold it")
}

@Composable
private fun FileFacts(job: Job, info: MediaInfo) {
    Section("Original file")
    Fact("Kind", if (info.isQuickTime) "QuickTime movie (MOV)" else "MP4-family file (${info.brand.trim()})")
    Labels.size(job.size).takeIf { it.isNotEmpty() }?.let { Fact("Size", it) }
    Labels.duration(info.durationUs).takeIf { it.isNotEmpty() }?.let { Fact("Length", it) }
    val meta = info.metadata
    (meta["com.apple.quicktime.creationdate"] ?: meta["creation_time"])?.let { raw ->
        Fact("Recorded", prettyDate(raw))
    }
    val device = listOfNotNull(meta["com.apple.quicktime.make"], meta["com.apple.quicktime.model"])
        .joinToString(" ")
    if (device.isNotBlank()) Fact("Device", device)
    if (meta.keys.any { it.contains("location", ignoreCase = true) }) Fact("Location", "Saved in the file, kept")
}

private fun prettyDate(raw: String): String = runCatching {
    val instant = runCatching { Instant.parse(raw) }.getOrElse {
        java.time.OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant()
    }
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(instant)
}.getOrDefault(raw)

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Fact(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("MOV to MP4", style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
            AboutPoint(
                "What lossless means here",
                "MOV and MP4 are two kinds of wrapper around the same picture and sound. This app lifts the " +
                    "contents out of one wrapper and into the other without touching them. Nothing is " +
                    "re-encoded. Each MP4 is then read back and compared with the MOV, and it is only " +
                    "kept if the picture and sound match exactly.",
            )
            AboutPoint(
                "When it says no",
                "A few formats, such as ProRes, can't go inside an MP4 at all. Converting those would mean " +
                    "re-encoding and losing quality, so the app tells you and saves nothing.",
            )
            AboutPoint(
                "Where files go",
                "Every MP4 is saved to Movies › ${Converter.OUTPUT_FOLDER} and shows up in your gallery. " +
                    "The original MOV is never changed or deleted.",
            )
            AboutPoint(
                "Private",
                "Everything happens on this phone. The app has no internet access.",
            )
            Text(
                "Built on FFmpeg ${Remux.ffmpegVersion} (LGPL 2.1 or later), used only to read MOV and write MP4.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun AboutPoint(title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}
