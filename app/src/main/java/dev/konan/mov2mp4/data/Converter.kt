package dev.konan.mov2mp4.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.konan.mov2mp4.ConvertService
import dev.konan.mov2mp4.engine.Labels
import dev.konan.mov2mp4.engine.MediaInfo
import dev.konan.mov2mp4.engine.Remux
import dev.konan.mov2mp4.engine.RemuxResult
import dev.konan.mov2mp4.engine.TrackAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The conversion queue. One file is converted at a time; every screen and the
 * foreground service read the same [jobs] list.
 */
object Converter {
    const val OUTPUT_FOLDER = "MOV to MP4"
    private const val TAG = "Converter"
    // How much of the progress bar each engine phase gets: copy, finish, check.
    private val PHASE_START = floatArrayOf(0f, 0.45f, 0.75f, 1f)
    private const val SPACE_MARGIN = 32L * 1024 * 1024

    private class Handle(val pfd: ParcelFileDescriptor, val temp: File?) {
        val cancel = AtomicBoolean(false)
        val busy = AtomicBoolean(false)
        val discard = AtomicBoolean(false)

        fun close() {
            runCatching { pfd.close() }
            temp?.delete()
        }
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow<List<Job>>(emptyList())
    private val handles = ConcurrentHashMap<Long, Handle>()
    private val nextId = AtomicLong()
    private val turns = Channel<Long>(Channel.UNLIMITED)

    val jobs: StateFlow<List<Job>> = state.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        incomingDir().listFiles()?.forEach { it.delete() }
        scope.launch(Dispatchers.IO) {
            for (id in turns) {
                try {
                    convert(id)
                } catch (e: Throwable) {
                    Log.e(TAG, "conversion crashed", e)
                    setState(id, JobState.Failed("Couldn't convert", e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Adds files to the queue. Conversion starts by itself. */
    fun enqueue(uris: List<Uri>) {
        val active = state.value.filter { it.isActive }.map { it.source }.toSet()
        val fresh = uris.distinct().filterNot { it in active }.map {
            Job(id = nextId.incrementAndGet(), source = it, name = fallbackName(it))
        }
        if (fresh.isEmpty()) return
        state.update { fresh + it }
        ConvertService.start(app)
        fresh.forEach { job -> scope.launch(Dispatchers.IO) { prepare(job.id) } }
    }

    fun cancel(id: Long) {
        handles[id]?.cancel?.set(true)
        // A file that is mid-conversion turns Cancelled once the engine has stopped.
        update(id) {
            if (it.state == JobState.Preparing || it.state == JobState.Queued) it.copy(state = JobState.Cancelled)
            else it
        }
    }

    /** Puts a cancelled or failed file back in the queue. */
    fun retry(id: Long, withoutAudio: Boolean = false) {
        val handle = handles[id] ?: return
        handle.cancel.set(false)
        update(id) { it.copy(state = JobState.Queued, withoutAudio = it.withoutAudio || withoutAudio) }
        ConvertService.start(app)
        turns.trySend(id)
    }

    /** Takes a file off the list. The saved MP4, if any, stays in the gallery. */
    fun remove(id: Long) {
        state.update { list -> list.filterNot { it.id == id } }
        release(id)
    }

    fun clearFinished() {
        val gone = state.value.filterNot { it.isActive }.map { it.id }
        state.update { list -> list.filter { it.isActive } }
        gone.forEach(::release)
    }

    // ---- preparing ---------------------------------------------------------

    private fun prepare(id: Long) {
        val job = find(id) ?: return
        val resolver = app.contentResolver
        var name = job.name
        try {
            runCatching {
                resolver.query(job.source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
            update(id) { it.copy(name = name) }

            val handle = open(job.source)
            handles[id] = handle
            if (find(id) == null) {
                release(id)
                return
            }
            val size = runCatching { Os.fstat(handle.pfd.fileDescriptor).st_size }.getOrDefault(0L)
            val info = Remux.probe(handle.pfd.fd)
            if (!info.ok) {
                Log.w(TAG, "probe failed: ${info.errorCode} ${info.errorMessage}")
                update(id) { it.copy(size = size) }
                setState(
                    id,
                    JobState.Failed(
                        title = "Not a MOV video",
                        detail = "This file isn't a QuickTime movie, or it is damaged.",
                        technical = info.errorMessage,
                    ),
                )
                release(id)
                return
            }
            var queued = false
            update(id) {
                queued = false
                if (it.state == JobState.Preparing) {
                    queued = true
                    it.copy(size = size, info = info, state = JobState.Queued)
                } else {
                    it.copy(size = size, info = info)
                }
            }
            if (queued) turns.trySend(id)
            thumbnail(handle.pfd)?.let { bitmap -> update(id) { it.copy(thumbnail = bitmap) } }
        } catch (e: Throwable) {
            Log.w(TAG, "prepare failed", e)
            setState(
                id,
                JobState.Failed(
                    title = "Can't open this file",
                    detail = "The app that shared it didn't give access, or the file is gone.",
                    technical = e.message ?: e.javaClass.simpleName,
                ),
            )
            release(id)
        }
    }

    /**
     * The engine seeks, so it needs a real file. Most apps hand one over; when a
     * provider only streams, the bytes are spooled to the cache first.
     */
    private fun open(source: Uri): Handle {
        val resolver = app.contentResolver
        val pfd = runCatching { resolver.openFileDescriptor(source, "r") }.getOrNull()
        if (pfd != null && isSeekable(pfd)) return Handle(pfd, null)

        val temp = File.createTempFile("in-", ".mov", incomingDir())
        try {
            val input = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd)
            else resolver.openInputStream(source) ?: error("no data")
            input.use { src -> temp.outputStream().use { src.copyTo(it, 1 shl 20) } }
            return Handle(ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY), temp)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    private fun isSeekable(pfd: ParcelFileDescriptor): Boolean = runCatching {
        OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode) &&
            Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR) >= 0
    }.getOrDefault(false)

    private fun thumbnail(pfd: ParcelFileDescriptor): ImageBitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pfd.fileDescriptor)
            retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                ?.asImageBitmap()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    // ---- converting --------------------------------------------------------

    private fun convert(id: Long) {
        val job = find(id) ?: return
        if (job.state != JobState.Queued) return
        val handle = handles[id] ?: return
        val info = job.info ?: return

        val plan = Plan.of(info, job.withoutAudio, job.name)
        plan.failure?.let {
            fail(id, it)
            return
        }

        val free = runCatching {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (free < job.size + SPACE_MARGIN) {
            setState(
                id,
                JobState.Failed(
                    title = "Not enough storage",
                    detail = "This video needs ${Labels.size(job.size)} of free space and the phone has " +
                        "${Labels.size(free)}. Free some space, then try again.",
                    canRetry = true,
                ),
            )
            return
        }

        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName(job.name))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$OUTPUT_FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val output = resolver.insert(collection, values)
        if (output == null) {
            setState(id, JobState.Failed("Couldn't save", "The phone refused to create the MP4 file."))
            return
        }

        var published = false
        var keepSource = false
        handle.busy.set(true)
        try {
            val started = SystemClock.elapsedRealtime()
            setState(id, JobState.Converting(Remux.PHASE_COPY, 0f))
            val result = resolver.openFileDescriptor(output, "rw")!!.use { out ->
                val r = Remux.remux(handle.pfd.fd, out.fd) { phase, done, total ->
                    val part = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
                    val p = phase.coerceIn(0, 2)
                    val fraction = PHASE_START[p] + part * (PHASE_START[p + 1] - PHASE_START[p])
                    setState(id, JobState.Converting(phase, fraction))
                    !handle.cancel.get()
                }
                if (r.ok) runCatching { Os.fsync(out.fileDescriptor) }
                r
            }
            when {
                result.ok && !handle.cancel.get() -> {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(output, values, null, null)
                    published = true
                    val (name, size) = describe(output, outputName(job.name), result.outSize)
                    setState(
                        id,
                        JobState.Done(
                            output = output,
                            outputName = name,
                            outputSize = size,
                            elapsedMs = SystemClock.elapsedRealtime() - started,
                            notes = plan.notes + resultNotes(result),
                            copied = result.tracks,
                            caution = when {
                                info.isIncomplete || result.readError.isNotEmpty() -> "Source incomplete"
                                plan.silent -> "No sound"
                                else -> null
                            },
                        ),
                    )
                }
                result.ok || result.cancelled -> {
                    keepSource = true
                    setState(id, JobState.Cancelled)
                }
                else -> {
                    Log.w(TAG, "remux failed: ${result.code} ${result.message} ${result.log}")
                    val failure = failureOf(result)
                    keepSource = failure.canRetry
                    setState(id, failure)
                }
            }
        } finally {
            handle.busy.set(false)
            if (!published) runCatching { resolver.delete(output, null, null) }
            // The source stays open only while trying again makes sense: after a
            // cancel, or when the phone ran out of space.
            if (!keepSource || handle.discard.get()) release(id)
        }
    }

    private fun resultNotes(result: RemuxResult): List<String> = buildList {
        if (result.readError.isNotEmpty()) {
            add(
                "Reading the MOV stopped before its end (${result.readError}). " +
                    "Everything up to that point was copied."
            )
        }
        if (result.tsFixed > 0) {
            add(
                "${result.tsFixed} timestamps in the source ran backwards and were moved forward by " +
                    "one tick so the MP4 plays. Picture and sound data are unchanged."
            )
        }
    }

    private fun failureOf(result: RemuxResult): JobState.Failed {
        val technical = listOf(result.log.trim(), result.message.trim()).filter { it.isNotEmpty() }
            .joinToString("\n")
        return when (result.code) {
            "no_space" -> JobState.Failed(
                "Ran out of storage",
                "The phone filled up while saving. Free some space, then try again.",
                technical = technical,
                canRetry = true,
            )
            "open_failed" -> JobState.Failed(
                "Not a MOV video",
                "This file isn't a QuickTime movie, or it is damaged.",
                technical = technical,
            )
            "read_failed" -> JobState.Failed(
                "The file is damaged",
                "Reading stopped partway through, so nothing was saved.",
                technical = technical,
            )
            "encrypted" -> JobState.Failed(
                "Copy-protected video",
                "This video is encrypted. Its picture and sound can't be moved into an MP4.",
                technical = technical,
            )
            "no_data" -> JobState.Failed(
                "Nothing could be read",
                "The file has a header but no picture or sound behind it. It is probably an incomplete download.",
                technical = technical,
            )
            "verify_failed" -> JobState.Failed(
                "Couldn't make an exact copy",
                "The MP4 was written, read back and compared with the MOV. It did not match exactly, " +
                    "so it was thrown away.",
                technical = technical,
            )
            "no_streams" -> JobState.Failed(
                "Nothing to convert",
                "There is no picture or sound in this file that MP4 can hold.",
                technical = technical,
            )
            else -> JobState.Failed(
                "Couldn't convert",
                "MP4 refused this file as it is, so nothing was saved.",
                technical = technical,
            )
        }
    }

    private fun describe(uri: Uri, fallbackName: String, fallbackSize: Long): Pair<String, Long> {
        var name = fallbackName
        var size = fallbackSize
        runCatching {
            app.contentResolver.query(
                uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                    c.getLong(1).takeIf { it > 0 }?.let { size = it }
                }
            }
        }
        return name to size
    }

    // ---- helpers -----------------------------------------------------------

    private fun find(id: Long) = state.value.firstOrNull { it.id == id }

    private fun update(id: Long, change: (Job) -> Job) {
        state.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun setState(id: Long, next: JobState) {
        update(id) { it.copy(state = next) }
        // One line per finished file, for `adb logcat -s Converter`.
        val name = find(id)?.name ?: return
        when (next) {
            is JobState.Done -> Log.i(
                TAG,
                "RESULT|done|$name|${next.outputName}|${next.outputSize}|${next.elapsedMs}ms|" +
                    next.copied.joinToString(",") { "${it.type}#${it.input}:${it.packets}" } +
                    "|notes=${next.notes.size}|${next.caution ?: "-"}",
            )
            is JobState.Failed -> Log.i(TAG, "RESULT|failed|$name|${next.title}|${next.technical.replace('\n', ' ')}")
            JobState.Cancelled -> Log.i(TAG, "RESULT|cancelled|$name")
            else -> Unit
        }
    }

    /** A refusal: nothing to retry unless the user can change the outcome. */
    private fun fail(id: Long, failure: JobState.Failed) {
        setState(id, failure)
        if (!failure.canDropAudio && !failure.canRetry) release(id)
    }

    private fun release(id: Long) {
        val handle = handles[id] ?: return
        if (handle.busy.get()) {
            // Still inside the engine: stop it and let convert() close the file.
            handle.discard.set(true)
            handle.cancel.set(true)
            return
        }
        handles.remove(id)
        handle.close()
    }

    private fun incomingDir() = File(app.cacheDir, "incoming").apply { mkdirs() }

    private fun fallbackName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Video"

    private fun outputName(source: String): String {
        val base = source.substringBeforeLast('.', source)
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim().ifBlank { "Video" }
        return "$base.mp4"
    }
}

/** Decides, before any byte is written, whether a file can be rewrapped without loss. */
internal class Plan(val failure: JobState.Failed?, val notes: List<String>, val silent: Boolean = false) {
    companion object {
        /** Picture formats that ordinary players handle in an MP4. */
        private val WIDELY_PLAYED = setOf("h264", "hevc", "av1", "vp9", "mpeg4")

        fun of(info: MediaInfo, withoutAudio: Boolean, name: String = ""): Plan {
            // The app is offered for every video, so MP4s arrive too. A file that says it
            // is MP4 and isn't named .mov has nothing to gain from being rewrapped.
            val namedMov = name.endsWith(".mov", ignoreCase = true) || name.endsWith(".qt", ignoreCase = true)
            if (info.brand.isNotBlank() && !info.isQuickTime && !namedMov) {
                return Plan(
                    JobState.Failed(
                        title = "Already an MP4",
                        detail = "This file is not a MOV. It is already in the MP4 family, so there is nothing to convert.",
                    ),
                    emptyList(),
                )
            }
            val videos = info.tracks.filter { it.isVideo }
            val audios = info.tracks.filter { it.isAudio }
            val keptAudio = audios.filter { it.action == TrackAction.COPY }
            val lostAudio = audios.filter { it.action == TrackAction.UNSUPPORTED }

            info.tracks.firstOrNull { (it.isVideo || it.isAudio) && it.why == "encrypted" }?.let {
                return Plan(
                    JobState.Failed(
                        title = "Copy-protected video",
                        detail = "This video is encrypted. Its picture and sound can't be moved into an MP4.",
                    ),
                    emptyList(),
                )
            }
            videos.firstOrNull { it.action == TrackAction.UNSUPPORTED }?.let { v ->
                return Plan(
                    JobState.Failed(
                        title = "Can't be done without loss",
                        detail = "The picture is ${Labels.videoCodec(v)}. MP4 can't hold that format, so it " +
                            "would have to be re-encoded, and that loses quality. Nothing was saved.",
                    ),
                    emptyList(),
                )
            }
            if (videos.isEmpty() && keptAudio.isEmpty() && lostAudio.isNotEmpty()) {
                return Plan(
                    JobState.Failed(
                        title = "Can't be done without loss",
                        detail = "The sound is ${Labels.audioCodec(lostAudio.first())}. MP4 can't hold that " +
                            "format, so it would have to be re-encoded, and that loses quality. Nothing was saved.",
                    ),
                    emptyList(),
                )
            }
            if (videos.none { it.action == TrackAction.COPY } && keptAudio.isEmpty()) {
                return Plan(
                    JobState.Failed(
                        title = "Nothing to convert",
                        detail = "There is no picture or sound in this file that MP4 can hold.",
                    ),
                    emptyList(),
                )
            }
            if (lostAudio.isNotEmpty() && keptAudio.isEmpty() && !withoutAudio) {
                return Plan(
                    JobState.Failed(
                        title = "The sound can't be kept",
                        detail = "The sound is ${Labels.audioCodec(lostAudio.first())}, which MP4 can't hold. " +
                            "The picture can still be converted without loss, but it will be silent.",
                        canDropAudio = videos.isNotEmpty(),
                    ),
                    emptyList(),
                )
            }

            val notes = buildList {
                val missing = info.tracks.sumOf { it.missing }
                if (missing > 0) {
                    add(
                        "The MOV itself is incomplete: it ends before its last $missing " +
                            "${if (missing == 1L) "frame or sound packet" else "frames and sound packets"}, " +
                            "so the MP4 ends early too. If it was downloaded or received, try getting it again."
                    )
                }
                if (lostAudio.isNotEmpty() && keptAudio.isEmpty()) {
                    add("Converted without sound: the ${Labels.audioCodec(lostAudio.first())} track can't go into MP4.")
                } else if (lostAudio.isNotEmpty()) {
                    val what = lostAudio.joinToString(", ") { Labels.audioCodec(it) }
                    add(
                        "An extra audio track ($what) was left out because MP4 can't hold it. " +
                            "The main sound is untouched."
                    )
                }
                videos.firstOrNull { it.action == TrackAction.COPY && it.codec !in WIDELY_PLAYED }?.let { v ->
                    val name = Labels.videoCodec(v)
                    add(
                        "The picture is $name and was copied as it is. " +
                            "Many phones and apps can't play $name inside MP4."
                    )
                }
                if (keptAudio.any { it.codec.startsWith("pcm_") }) {
                    add(
                        "The sound is uncompressed PCM and was copied as it is. " +
                            "Some players can't play PCM sound inside MP4."
                    )
                }
                if (info.tracks.any { it.type == "subtitle" && it.action == TrackAction.UNSUPPORTED }) {
                    add("Captions were left out because MP4 can't hold this caption format.")
                }
                val extras = info.tracks.count {
                    it.type == "data" && it.why == "data" && it.tag.trim().lowercase() != "tmcd"
                }
                if (extras > 0) {
                    add(
                        "$extras extra data ${if (extras == 1) "track was" else "tracks were"} left out. " +
                            "They carry device notes such as Live Photo markers, not picture or sound."
                    )
                }
            }
            return Plan(null, notes, silent = lostAudio.isNotEmpty() && keptAudio.isEmpty())
        }
    }
}
