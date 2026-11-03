package dev.konan.mov2mp4.engine

import org.json.JSONObject

/** Kotlin face of native/remux.c: a stream-copy MOV to MP4 rewrap. Nothing is re-encoded. */
object Remux {
    const val PHASE_COPY = 0
    const val PHASE_FINISH = 1
    const val PHASE_VERIFY = 2

    init {
        System.loadLibrary("movmp4")
    }

    /** Called from the native loop about twelve times a second. Return false to cancel. */
    fun interface Progress {
        fun onProgress(phase: Int, done: Long, total: Long): Boolean
    }

    @JvmStatic private external fun nativeProbe(fd: Int): ByteArray
    @JvmStatic private external fun nativeRemux(inFd: Int, outFd: Int, progress: Progress?): ByteArray
    @JvmStatic private external fun nativeVersion(): String

    val ffmpegVersion: String by lazy { runCatching { nativeVersion() }.getOrDefault("") }

    /** Reads the container header of a seekable file descriptor. */
    fun probe(fd: Int): MediaInfo = MediaInfo.parse(json(nativeProbe(fd)))

    /**
     * Copies every track MP4 can hold from [inFd] to [outFd], then reads the MP4 back and
     * compares it with what was copied. Both descriptors must be seekable.
     */
    fun remux(inFd: Int, outFd: Int, progress: Progress?): RemuxResult =
        RemuxResult.parse(json(nativeRemux(inFd, outFd, progress)))

    // Metadata in old files is not always valid UTF-8, so the native side hands back
    // bytes and malformed sequences are replaced here instead of crashing JNI.
    private fun json(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))
}

enum class TrackAction { COPY, SKIP, UNSUPPORTED }

data class Track(
    val index: Int,
    val type: String,
    val codec: String,
    val codecLong: String,
    val tag: String,
    val action: TrackAction,
    val why: String,
    val frames: Long,
    /** Samples the file's index lists but the file is too short to contain. */
    val missing: Long,
    val bitRate: Long,
    val isDefault: Boolean,
    val isCover: Boolean,
    val durationUs: Long,
    val language: String?,
    val timecode: String?,
    val width: Int,
    val height: Int,
    val fps: Double,
    val rotation: Int,
    val bitDepth: Int,
    val profile: String,
    val transfer: String,
    val primaries: String,
    val dolbyVision: String?,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val layout: String,
) {
    val isVideo get() = type == "video" && !isCover
    val isAudio get() = type == "audio"

    /** Width and height as shown on screen, after the rotation flag. */
    val displayWidth get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight get() = if (rotation == 90 || rotation == 270) width else height
}

data class MediaInfo(
    val ok: Boolean,
    val errorCode: String,
    val errorMessage: String,
    val container: String,
    val brand: String,
    val durationUs: Long,
    val bitRate: Long,
    val size: Long,
    val chapters: Int,
    val metadata: Map<String, String>,
    val tracks: List<Track>,
) {
    val video get() = tracks.firstOrNull { it.isVideo && it.isDefault } ?: tracks.firstOrNull { it.isVideo }
    val audio get() = tracks.firstOrNull { it.isAudio && it.isDefault } ?: tracks.firstOrNull { it.isAudio }
    val isQuickTime get() = brand.trim().equals("qt", ignoreCase = true)

    /** The file ends before its own index says it should. */
    val isIncomplete get() = tracks.any { it.missing > 0 }

    companion object {
        fun parse(o: JSONObject): MediaInfo {
            val ok = o.optBoolean("ok")
            val meta = buildMap {
                o.optJSONObject("metadata")?.let { m -> m.keys().forEach { put(it, m.optString(it)) } }
            }
            val tracks = buildList {
                val arr = o.optJSONArray("streams") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    add(
                        Track(
                            index = s.optInt("index"),
                            type = s.optString("type"),
                            codec = s.optString("codec"),
                            codecLong = s.optString("codecLong"),
                            tag = s.optString("tag"),
                            action = when (s.optString("action")) {
                                "copy" -> TrackAction.COPY
                                "skip" -> TrackAction.SKIP
                                else -> TrackAction.UNSUPPORTED
                            },
                            why = s.optString("why"),
                            frames = s.optLong("frames"),
                            missing = s.optLong("missing"),
                            bitRate = s.optLong("bitRate"),
                            isDefault = s.optBoolean("default"),
                            isCover = s.optBoolean("cover"),
                            durationUs = s.optLong("durationUs"),
                            language = s.optString("language").takeIf { it.isNotBlank() && it != "und" },
                            timecode = s.optString("timecode").takeIf { it.isNotBlank() },
                            width = s.optInt("width"),
                            height = s.optInt("height"),
                            fps = s.optDouble("fps", 0.0),
                            rotation = s.optInt("rotation"),
                            bitDepth = s.optInt("bitDepth"),
                            profile = s.optString("profile"),
                            transfer = s.optString("transfer"),
                            primaries = s.optString("primaries"),
                            dolbyVision = s.optString("dolbyVision").takeIf { it.isNotBlank() },
                            sampleRate = s.optInt("sampleRate"),
                            channels = s.optInt("channels"),
                            bitsPerSample = s.optInt("bitsPerSample"),
                            layout = s.optString("layout"),
                        )
                    )
                }
            }
            return MediaInfo(
                ok = ok,
                errorCode = o.optString("code"),
                errorMessage = o.optString("log").ifBlank { o.optString("message") },
                container = o.optString("container"),
                brand = o.optString("brand"),
                durationUs = o.optLong("durationUs"),
                bitRate = o.optLong("bitRate"),
                size = o.optLong("size"),
                chapters = o.optInt("chapters"),
                metadata = meta,
                tracks = tracks,
            )
        }
    }
}

data class CopiedTrack(val input: Int, val type: String, val packets: Long, val bytes: Long, val tsFixed: Int)

data class RemuxResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val log: String,
    val errno: Int,
    val outSize: Long,
    val appleMetadata: Boolean,
    val tsFixed: Long,
    /** Set when reading the source stopped early; what was read is still saved. */
    val readError: String,
    val tracks: List<CopiedTrack>,
) {
    val cancelled get() = code == "cancelled"

    companion object {
        fun parse(o: JSONObject): RemuxResult {
            val tracks = buildList {
                val arr = o.optJSONArray("streams") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    add(
                        CopiedTrack(
                            input = s.optInt("in"),
                            type = s.optString("type"),
                            packets = s.optLong("packets"),
                            bytes = s.optLong("bytes"),
                            tsFixed = s.optInt("tsFixed"),
                        )
                    )
                }
            }
            return RemuxResult(
                ok = o.optBoolean("ok"),
                code = o.optString("code"),
                message = o.optString("message"),
                log = o.optString("log"),
                errno = o.optInt("errno"),
                outSize = o.optLong("outSize"),
                appleMetadata = o.optBoolean("appleMetadata"),
                tsFixed = o.optLong("tsFixed"),
                readError = o.optString("readError"),
                tracks = tracks,
            )
        }
    }
}
