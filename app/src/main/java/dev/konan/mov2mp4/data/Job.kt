package dev.konan.mov2mp4.data

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import dev.konan.mov2mp4.engine.CopiedTrack
import dev.konan.mov2mp4.engine.MediaInfo

sealed interface JobState {
    /** Reading the file header. */
    data object Preparing : JobState

    /** Header read, waiting for its turn. */
    data object Queued : JobState

    /** [fraction] runs 0..1 across both phases. */
    data class Converting(val phase: Int, val fraction: Float) : JobState

    data class Done(
        val output: Uri,
        val outputName: String,
        val outputSize: Long,
        val elapsedMs: Long,
        val notes: List<String>,
        val copied: List<CopiedTrack>,
        /** Short warning shown on the card, e.g. "Source incomplete". Null when all is well. */
        val caution: String? = null,
    ) : JobState

    data class Failed(
        val title: String,
        val detail: String,
        /** The picture can be converted if the user accepts losing the sound. */
        val canDropAudio: Boolean = false,
        /** Trying again can work, e.g. after freeing storage. */
        val canRetry: Boolean = false,
        val technical: String = "",
    ) : JobState

    data object Cancelled : JobState
}

data class Job(
    val id: Long,
    val source: Uri,
    val name: String,
    val size: Long = 0,
    val info: MediaInfo? = null,
    val thumbnail: ImageBitmap? = null,
    val state: JobState = JobState.Preparing,
    val withoutAudio: Boolean = false,
) {
    val isActive get() = state is JobState.Preparing || state is JobState.Queued || state is JobState.Converting
}
