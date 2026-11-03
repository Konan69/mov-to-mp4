package dev.konan.mov2mp4.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Plain-language names for what the engine reports. */
object Labels {
    fun videoCodec(t: Track): String = when (t.codec) {
        "hevc" -> "HEVC"
        "h264" -> "H.264"
        "prores" -> when (t.tag.lowercase()) {
            "apco" -> "ProRes 422 Proxy"
            "apcs" -> "ProRes 422 LT"
            "apcn" -> "ProRes 422"
            "apch" -> "ProRes 422 HQ"
            "ap4h" -> "ProRes 4444"
            "ap4x" -> "ProRes 4444 XQ"
            else -> "ProRes"
        }
        "prores_raw" -> "ProRes RAW"
        "mjpeg" -> "Motion JPEG"
        "mpeg4" -> "MPEG-4"
        "mpeg2video" -> "MPEG-2"
        "mpeg1video" -> "MPEG-1"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "vvc" -> "VVC"
        "dnxhd" -> "DNxHD"
        "dvvideo" -> "DV"
        "qtrle" -> "QuickTime Animation"
        "rawvideo" -> "uncompressed video"
        "svq1" -> "Sorenson Video"
        "svq3" -> "Sorenson Video 3"
        "cinepak" -> "Cinepak"
        "h263" -> "H.263"
        "png" -> "PNG"
        "hap" -> "HAP"
        "cfhd" -> "CineForm"
        "rpza" -> "Apple Video"
        "smc" -> "Apple Graphics"
        "none", "" -> if (t.tag.isNotBlank()) "an unknown format (${t.tag.trim()})" else "an unknown format"
        else -> t.codec.uppercase(Locale.ROOT)
    }

    fun audioCodec(t: Track): String = when {
        t.codec == "aac" -> "AAC"
        t.codec == "alac" -> "Apple Lossless"
        t.codec == "mp3" -> "MP3"
        t.codec == "mp2" -> "MP2"
        t.codec == "ac3" -> "Dolby Digital"
        t.codec == "eac3" -> "Dolby Digital Plus"
        t.codec == "opus" -> "Opus"
        t.codec == "flac" -> "FLAC"
        t.codec == "pcm_mulaw" -> "µ-law PCM"
        t.codec == "pcm_alaw" -> "A-law PCM"
        t.codec.startsWith("pcm_") -> if (t.bitsPerSample > 0) "${t.bitsPerSample}-bit PCM" else "PCM"
        t.codec.startsWith("adpcm") -> "ADPCM"
        t.codec == "qdm2" || t.codec == "qdmc" -> "QDesign Music"
        t.codec == "qcelp" -> "QCELP"
        t.codec == "amr_nb" || t.codec == "amr_wb" -> "AMR"
        t.codec == "none" || t.codec.isBlank() -> when (t.tag.trim().lowercase()) {
            "apac" -> "Apple spatial audio"
            "" -> "an unknown format"
            else -> "an unknown format (${t.tag.trim()})"
        }
        else -> t.codec.uppercase(Locale.ROOT)
    }

    fun resolution(t: Track): String {
        val w = t.displayWidth
        val h = t.displayHeight
        if (w <= 0 || h <= 0) return ""
        val short = min(w, h)
        val long = maxOf(w, h)
        return when {
            long >= 7680 -> "8K"
            long >= 3840 || short >= 2160 -> "4K"
            short >= 1440 -> "1440p"
            short >= 1080 -> "1080p"
            short >= 720 -> "720p"
            else -> "$w×$h"
        }
    }

    fun hdr(t: Track): String? = when {
        t.dolbyVision != null -> "Dolby Vision"
        t.transfer == "arib-std-b67" -> "HLG HDR"
        t.transfer == "smpte2084" -> "HDR10"
        else -> null
    }

    fun fps(value: Double): String {
        if (value <= 0 || value.isNaN()) return ""
        val rounded = value.roundToInt()
        return if (abs(value - rounded) < 0.005) "$rounded fps"
        else String.format(Locale.US, "%.2f fps", value)
    }

    fun channels(t: Track): String = when (t.channels) {
        0 -> ""
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${t.channels} channels"
    }

    fun sampleRate(hz: Int): String = when {
        hz <= 0 -> ""
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> String.format(Locale.US, "%.1f kHz", hz / 1000.0)
    }

    fun bitRate(bps: Long): String = when {
        bps <= 0 -> ""
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else -> "${(bps / 1000.0).roundToLong()} kbps"
    }

    fun duration(us: Long): String {
        if (us <= 0) return ""
        val total = (us / 1_000_000.0).roundToLong().coerceAtLeast(1)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
        bytes < 10_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes < 1_000_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    }

    fun elapsed(ms: Long): String = when {
        ms < 950 -> "under a second"
        ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
        else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
    }

    /** One line for a card: "4K · HEVC · Dolby Vision · 0:42". */
    fun summary(info: MediaInfo): String {
        val parts = mutableListOf<String>()
        val v = info.video
        val a = info.audio
        if (v != null) {
            resolution(v).takeIf { it.isNotEmpty() }?.let(parts::add)
            parts += videoCodec(v)
            hdr(v)?.let(parts::add)
        } else if (a != null) {
            parts += audioCodec(a) + " audio"
        }
        duration(info.durationUs).takeIf { it.isNotEmpty() }?.let(parts::add)
        return parts.joinToString("  ·  ")
    }
}
