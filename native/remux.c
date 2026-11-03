/*
 * Lossless MOV -> MP4 rewrap on top of libavformat.
 *
 * Every packet is copied untouched from the QuickTime demuxer into the MP4
 * muxer; nothing is decoded or encoded. The stream setup mirrors what
 * `ffmpeg -c copy` does (fftools/ffmpeg_mux_init.c streamcopy_init and
 * fftools/ffmpeg_mux.c mux_fixup_ts), except that every video, audio and
 * subtitle track is carried over and creation time is kept.
 *
 * After the MP4 is written it is read back and every track's bytes are compared
 * with what came out of the MOV, so "lossless" is checked per file, not assumed.
 *
 * All I/O goes through pread/pwrite on file descriptors handed in from Kotlin,
 * so a content:// document never has to be copied to a path first.
 */
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <errno.h>
#include <jni.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>

#include <libavcodec/avcodec.h>
#include <libavcodec/codec_desc.h>
#include <libavformat/avformat.h>
#include <libavutil/avstring.h>
#include <libavutil/avutil.h>
#include <libavutil/bprint.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/mathematics.h>
#include <libavutil/murmur3.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

#define TAG "movmp4"
#define IO_BUFFER (256 * 1024)
#define PROGRESS_INTERVAL_US 80000

enum { PHASE_COPY = 0, PHASE_FINISH = 1, PHASE_VERIFY = 2 };
enum { ACT_COPY = 0, ACT_SKIP = 1, ACT_UNSUPPORTED = 2 };

typedef struct Session {
    JNIEnv *env;
    jobject listener;
    jmethodID on_progress;
    int cancelled;
    int phase;
    int64_t done;
    int64_t total;
    int64_t last_emit;
    int out_fd;
    int io_errno;
    char log[768];
} Session;

static __thread Session *tls_session;

/* ---- logging ---------------------------------------------------------- */

static void log_callback(void *avcl, int level, const char *fmt, va_list vl)
{
    char line[512];
    (void)avcl;
    if (level > AV_LOG_WARNING)
        return;
    vsnprintf(line, sizeof(line), fmt, vl);
    __android_log_print(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                        TAG, "%s", line);
    if (tls_session && level <= AV_LOG_ERROR) {
        Session *s = tls_session;
        size_t have = strlen(s->log), add = strlen(line);
        if (add >= sizeof(s->log))
            add = sizeof(s->log) - 1;
        if (have + add >= sizeof(s->log)) {
            size_t drop = have + add - (sizeof(s->log) - 1);
            memmove(s->log, s->log + drop, have - drop);
            have -= drop;
        }
        memcpy(s->log + have, line, add);
        s->log[have + add] = 0;
    }
}

/* ---- progress --------------------------------------------------------- */

static void emit_progress(Session *s, int force)
{
    int64_t now;
    jboolean keep_going;

    if (!s->listener)
        return;
    now = av_gettime_relative();
    if (!force && now - s->last_emit < PROGRESS_INTERVAL_US)
        return;
    s->last_emit = now;
    keep_going = (*s->env)->CallBooleanMethod(s->env, s->listener, s->on_progress,
                                              (jint)s->phase, (jlong)s->done, (jlong)s->total);
    if ((*s->env)->ExceptionCheck(s->env)) {
        (*s->env)->ExceptionClear(s->env);
        keep_going = JNI_FALSE;
    }
    if (!keep_going)
        s->cancelled = 1;
}

static int interrupt_cb(void *opaque)
{
    return ((Session *)opaque)->cancelled;
}

/* ---- file descriptor I/O ---------------------------------------------- */

typedef struct FdIO {
    int fd;
    int64_t pos;
    Session *s;
} FdIO;

static int fd_read(void *opaque, uint8_t *buf, int size)
{
    FdIO *io = opaque;
    ssize_t n;

    if (io->s->cancelled)
        return AVERROR_EXIT;
    do {
        n = pread64(io->fd, buf, size, io->pos);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        io->s->io_errno = errno;
        return AVERROR(errno);
    }
    if (n == 0)
        return AVERROR_EOF;
    io->pos += n;
    return (int)n;
}

static int fd_write(void *opaque, const uint8_t *buf, int size)
{
    FdIO *io = opaque;
    Session *s = io->s;
    int left = size;

    if (s->cancelled)
        return AVERROR_EXIT;
    while (left > 0) {
        ssize_t n = pwrite64(io->fd, buf, left, io->pos);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            s->io_errno = errno;
            return AVERROR(errno);
        }
        buf += n;
        io->pos += n;
        left -= (int)n;
    }
    if (s->phase == PHASE_FINISH) {
        s->done += size;
        emit_progress(s, 0);
    }
    return size;
}

static int64_t fd_seek(void *opaque, int64_t offset, int whence)
{
    FdIO *io = opaque;
    struct stat64 st;
    int64_t target;

    if (whence & AVSEEK_SIZE) {
        if (fstat64(io->fd, &st) < 0)
            return AVERROR(errno);
        return st.st_size;
    }
    whence &= ~AVSEEK_FORCE;
    switch (whence) {
    case SEEK_SET:
        target = offset;
        break;
    case SEEK_CUR:
        target = io->pos + offset;
        break;
    case SEEK_END:
        if (fstat64(io->fd, &st) < 0)
            return AVERROR(errno);
        target = st.st_size + offset;
        break;
    default:
        return AVERROR(EINVAL);
    }
    if (target < 0)
        return AVERROR(EINVAL);
    io->pos = target;
    return target;
}

static AVIOContext *fd_io_open(int fd, int write, Session *s)
{
    FdIO *io = av_mallocz(sizeof(*io));
    uint8_t *buffer = av_malloc(IO_BUFFER);
    AVIOContext *pb;

    if (!io || !buffer) {
        av_free(io);
        av_free(buffer);
        return NULL;
    }
    io->fd = fd;
    io->s = s;
    pb = avio_alloc_context(buffer, IO_BUFFER, write, io,
                            write ? NULL : fd_read, write ? fd_write : NULL, fd_seek);
    if (!pb) {
        av_free(io);
        av_free(buffer);
    }
    return pb;
}

static void fd_io_close(AVIOContext **pb)
{
    if (!pb || !*pb)
        return;
    av_freep(&(*pb)->opaque);
    av_freep(&(*pb)->buffer);
    avio_context_free(pb);
}

/* The MP4 muxer re-opens its output for reading while it moves the index to
 * the front of the file (faststart). Hand it a second reader on the same fd. */
static int out_io_open(AVFormatContext *s, AVIOContext **pb, const char *url, int flags,
                       AVDictionary **options)
{
    Session *ses = s->opaque;
    (void)url;
    (void)options;
    if (flags & AVIO_FLAG_WRITE)
        return AVERROR(ENOSYS);
    *pb = fd_io_open(ses->out_fd, 0, ses);
    return *pb ? 0 : AVERROR(ENOMEM);
}

static int out_io_close2(AVFormatContext *s, AVIOContext *pb)
{
    (void)s;
    fd_io_close(&pb);
    return 0;
}

/* ---- JSON helpers ----------------------------------------------------- */

static void js_str(AVBPrint *b, const char *str)
{
    const unsigned char *p = (const unsigned char *)(str ? str : "");
    av_bprint_chars(b, '"', 1);
    for (; *p; p++) {
        switch (*p) {
        case '"':
            av_bprintf(b, "\\\"");
            break;
        case '\\':
            av_bprintf(b, "\\\\");
            break;
        case '\n':
            av_bprintf(b, "\\n");
            break;
        case '\r':
            av_bprintf(b, "\\r");
            break;
        case '\t':
            av_bprintf(b, "\\t");
            break;
        default:
            if (*p < 0x20)
                av_bprintf(b, "\\u%04x", *p);
            else
                av_bprint_chars(b, (char)*p, 1);
        }
    }
    av_bprint_chars(b, '"', 1);
}

static void js_key_str(AVBPrint *b, const char *key, const char *value)
{
    av_bprintf(b, ",\"%s\":", key);
    js_str(b, value);
}

static jbyteArray to_bytes(JNIEnv *env, AVBPrint *b)
{
    jbyteArray out = (*env)->NewByteArray(env, (jsize)b->len);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)b->len, (const jbyte *)b->str);
    av_bprint_finalize(b, NULL);
    return out;
}

static void js_error(AVBPrint *b, const char *code, int averr, const Session *s)
{
    char msg[AV_ERROR_MAX_STRING_SIZE] = "";
    if (averr)
        av_strerror(averr, msg, sizeof(msg));
    av_bprint_clear(b);
    av_bprintf(b, "{\"ok\":false,\"code\":\"%s\"", code);
    js_key_str(b, "message", msg);
    js_key_str(b, "log", s ? s->log : "");
    av_bprintf(b, ",\"errno\":%d}", s ? s->io_errno : 0);
}

/* ---- stream decisions ------------------------------------------------- */

static const char *type_name(enum AVMediaType t)
{
    switch (t) {
    case AVMEDIA_TYPE_VIDEO:
        return "video";
    case AVMEDIA_TYPE_AUDIO:
        return "audio";
    case AVMEDIA_TYPE_SUBTITLE:
        return "subtitle";
    case AVMEDIA_TYPE_DATA:
        return "data";
    case AVMEDIA_TYPE_ATTACHMENT:
        return "attachment";
    default:
        return "other";
    }
}

/* Formats whose MP4 form is well defined and which were run through this
 * engine and compared. FFmpeg's MP4 muxer accepts more (JPEG 2000, PNG, VC-1,
 * QCELP...), but for those the QuickTime-only boxes that describe the picture
 * or sound get lost on the way, so they are refused instead. */
static int is_carried(enum AVCodecID id)
{
    switch (id) {
    case AV_CODEC_ID_H264:
    case AV_CODEC_ID_HEVC:
    case AV_CODEC_ID_VVC:
    case AV_CODEC_ID_AV1:
    case AV_CODEC_ID_VP9:
    case AV_CODEC_ID_MPEG4:
    case AV_CODEC_ID_MPEG2VIDEO:
    case AV_CODEC_ID_MPEG1VIDEO:
    case AV_CODEC_ID_MJPEG:
    case AV_CODEC_ID_AAC:
    case AV_CODEC_ID_ALAC:
    case AV_CODEC_ID_MP3:
    case AV_CODEC_ID_MP2:
    case AV_CODEC_ID_AC3:
    case AV_CODEC_ID_EAC3:
    case AV_CODEC_ID_OPUS:
    case AV_CODEC_ID_FLAC:
    case AV_CODEC_ID_PCM_S16BE:
    case AV_CODEC_ID_PCM_S16LE:
    case AV_CODEC_ID_PCM_S24BE:
    case AV_CODEC_ID_PCM_S24LE:
    case AV_CODEC_ID_PCM_S32BE:
    case AV_CODEC_ID_PCM_S32LE:
    case AV_CODEC_ID_PCM_F32BE:
    case AV_CODEC_ID_PCM_F32LE:
    case AV_CODEC_ID_PCM_F64BE:
    case AV_CODEC_ID_PCM_F64LE:
    case AV_CODEC_ID_MOV_TEXT:
        return 1;
    default:
        return 0;
    }
}

/* What happens to a source track. Only tracks MP4 can hold as they are get
 * copied; nothing is ever converted. */
static int decide(const AVOutputFormat *ofmt, const AVStream *st, const char **why)
{
    const AVCodecParameters *par = st->codecpar;

    *why = "";
    if (st->discard == AVDISCARD_ALL) {
        *why = "inactive";
        return ACT_SKIP;
    }
    if (par->codec_type != AVMEDIA_TYPE_VIDEO && par->codec_type != AVMEDIA_TYPE_AUDIO &&
        par->codec_type != AVMEDIA_TYPE_SUBTITLE) {
        *why = "data";
        return ACT_SKIP;
    }
    if (av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data,
                                AV_PKT_DATA_ENCRYPTION_INIT_INFO)) {
        *why = "encrypted";
        return ACT_UNSUPPORTED;
    }
    if (par->codec_id == AV_CODEC_ID_NONE) {
        *why = "unknown";
        return ACT_UNSUPPORTED;
    }
    if (avformat_query_codec(ofmt, par->codec_id, FF_COMPLIANCE_UNOFFICIAL) != 1 ||
        !is_carried(par->codec_id)) {
        *why = "container";
        return ACT_UNSUPPORTED;
    }
    /* Avid's Motion JPEG keeps its field layout in Avid-only boxes that MP4 has
     * no place for; without them the picture decodes at a different height. */
    if (par->codec_id == AV_CODEC_ID_MJPEG &&
        (par->codec_tag == MKTAG('A', 'V', 'D', 'J') || par->codec_tag == MKTAG('A', 'V', 'R', 'n') ||
         par->codec_tag == MKTAG('A', 'D', 'J', 'V'))) {
        *why = "container";
        return ACT_UNSUPPORTED;
    }
    if (par->codec_type == AVMEDIA_TYPE_VIDEO && (par->width > 65535 || par->height > 65535)) {
        *why = "container";
        return ACT_UNSUPPORTED;
    }
    return ACT_COPY;
}

static const char *h264_profile(int idc)
{
    switch (idc) {
    case 66:
        return "Baseline";
    case 77:
        return "Main";
    case 88:
        return "Extended";
    case 100:
        return "High";
    case 110:
        return "High 10";
    case 122:
        return "High 4:2:2";
    case 244:
        return "High 4:4:4";
    default:
        return "";
    }
}

static const char *hevc_profile(int idc)
{
    switch (idc) {
    case 1:
        return "Main";
    case 2:
        return "Main 10";
    case 3:
        return "Main Still Picture";
    case 4:
        return "Range Extensions";
    default:
        return "";
    }
}

/* Samples the index promises but the file is too short to hold: a download
 * that was cut off, for example. */
static int64_t samples_past_eof(AVStream *st, int64_t file_size)
{
    int count = avformat_index_get_entries_count(st);
    int64_t missing = 0;

    if (file_size <= 0)
        return 0;
    for (int i = 0; i < count; i++) {
        const AVIndexEntry *e = avformat_index_get_entry(st, i);
        if (e && e->size > 0 && e->pos + e->size > file_size)
            missing++;
    }
    return missing;
}

static void describe_stream(AVBPrint *b, const AVOutputFormat *ofmt, AVStream *st, int64_t file_size)
{
    const AVCodecParameters *par = st->codecpar;
    const AVCodecDescriptor *desc = avcodec_descriptor_get(par->codec_id);
    const AVDictionaryEntry *e;
    const AVPacketSideData *sd;
    const char *why;
    int action = decide(ofmt, st, &why);
    int64_t bit_rate = par->bit_rate;

    av_bprintf(b, "{\"index\":%d", st->index);
    js_key_str(b, "type", type_name(par->codec_type));
    js_key_str(b, "codec", desc ? desc->name : "none");
    js_key_str(b, "codecLong", desc && desc->long_name ? desc->long_name : "");
    js_key_str(b, "tag", par->codec_tag ? av_fourcc2str(par->codec_tag) : "");
    js_key_str(b, "action", action == ACT_COPY ? "copy" : action == ACT_SKIP ? "skip" : "unsupported");
    js_key_str(b, "why", why);
    av_bprintf(b, ",\"frames\":%" PRId64, st->nb_frames);
    av_bprintf(b, ",\"indexed\":%d", avformat_index_get_entries_count(st));
    av_bprintf(b, ",\"missing\":%" PRId64, action == ACT_COPY ? samples_past_eof(st, file_size) : (int64_t)0);
    av_bprintf(b, ",\"bitRate\":%" PRId64, bit_rate > 0 ? bit_rate : 0);
    av_bprintf(b, ",\"default\":%s", (st->disposition & AV_DISPOSITION_DEFAULT) ? "true" : "false");
    av_bprintf(b, ",\"cover\":%s", (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ? "true" : "false");
    if (st->duration != AV_NOPTS_VALUE)
        av_bprintf(b, ",\"durationUs\":%" PRId64,
                   av_rescale_q(st->duration, st->time_base, AV_TIME_BASE_Q));
    if ((e = av_dict_get(st->metadata, "language", NULL, 0)))
        js_key_str(b, "language", e->value);
    if ((e = av_dict_get(st->metadata, "timecode", NULL, 0)))
        js_key_str(b, "timecode", e->value);

    if (par->codec_type == AVMEDIA_TYPE_VIDEO) {
        double fps = 0;
        int rotation = 0, depth = 0;
        const char *profile = "";

        if (st->avg_frame_rate.num > 0 && st->avg_frame_rate.den > 0)
            fps = av_q2d(st->avg_frame_rate);
        else if (st->r_frame_rate.num > 0 && st->r_frame_rate.den > 0)
            fps = av_q2d(st->r_frame_rate);
        sd = av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data,
                                     AV_PKT_DATA_DISPLAYMATRIX);
        if (sd && sd->size >= 9 * sizeof(int32_t)) {
            double r = av_display_rotation_get((const int32_t *)sd->data);
            if (r == r) { /* not NaN */
                rotation = (int)(-r + (r < 0 ? 0.5 : -0.5));
                rotation = ((rotation % 360) + 360) % 360;
            }
        }
        if (par->codec_id == AV_CODEC_ID_H264 && par->extradata_size >= 4 && par->extradata[0] == 1) {
            profile = h264_profile(par->extradata[1]);
            depth = par->extradata[1] == 110 ? 10 : 8;
        } else if (par->codec_id == AV_CODEC_ID_HEVC && par->extradata_size >= 19 &&
                   par->extradata[0] == 1) {
            profile = hevc_profile(par->extradata[1] & 0x1f);
            depth = (par->extradata[17] & 7) + 8;
        }
        av_bprintf(b, ",\"width\":%d,\"height\":%d,\"fps\":%.3f,\"rotation\":%d,\"bitDepth\":%d",
                   par->width, par->height, fps, rotation, depth);
        js_key_str(b, "profile", profile);
        js_key_str(b, "transfer",
                   par->color_trc != AVCOL_TRC_UNSPECIFIED && av_color_transfer_name(par->color_trc)
                       ? av_color_transfer_name(par->color_trc) : "");
        js_key_str(b, "primaries",
                   par->color_primaries != AVCOL_PRI_UNSPECIFIED &&
                           av_color_primaries_name(par->color_primaries)
                       ? av_color_primaries_name(par->color_primaries) : "");
        sd = av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data,
                                     AV_PKT_DATA_DOVI_CONF);
        if (sd && sd->size >= sizeof(AVDOVIDecoderConfigurationRecord)) {
            const AVDOVIDecoderConfigurationRecord *dv = (const void *)sd->data;
            av_bprintf(b, ",\"dolbyVision\":\"%d.%d\"", dv->dv_profile,
                       dv->dv_bl_signal_compatibility_id);
        }
    } else if (par->codec_type == AVMEDIA_TYPE_AUDIO) {
        char layout[64] = "";
        if (par->ch_layout.nb_channels > 0)
            av_channel_layout_describe(&par->ch_layout, layout, sizeof(layout));
        av_bprintf(b, ",\"sampleRate\":%d,\"channels\":%d,\"bitsPerSample\":%d", par->sample_rate,
                   par->ch_layout.nb_channels, av_get_bits_per_sample(par->codec_id));
        js_key_str(b, "layout", layout);
    }
    av_bprint_chars(b, '}', 1);
}

/* ---- opening the source ----------------------------------------------- */

/* raw_samples: ignore edit lists and return every stored sample, which is what
 * the read-back check wants. */
static int open_input(Session *s, int fd, AVFormatContext **out, AVIOContext **pb, int raw_samples)
{
    AVFormatContext *ifmt = avformat_alloc_context();
    const AVInputFormat *mov = av_find_input_format("mov");
    AVDictionary *opts = NULL;
    int ret;

    *out = NULL;
    *pb = NULL;
    if (!ifmt)
        return AVERROR(ENOMEM);
    *pb = fd_io_open(fd, 0, s);
    if (!*pb) {
        avformat_free_context(ifmt);
        return AVERROR(ENOMEM);
    }
    ifmt->pb = *pb;
    ifmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    ifmt->interrupt_callback.callback = interrupt_cb;
    ifmt->interrupt_callback.opaque = s;

    /* On failure avformat_open_input frees the context but, with custom I/O,
     * leaves the AVIOContext to the caller. */
    if (raw_samples)
        av_dict_set(&opts, "ignore_editlist", "1", 0);
    ret = avformat_open_input(&ifmt, NULL, mov, &opts);
    av_dict_free(&opts);
    if (ret < 0)
        return ret;
    *out = ifmt;
    ret = avformat_find_stream_info(ifmt, NULL);
    return ret < 0 ? ret : 0;
}

static void close_input(AVFormatContext **ifmt, AVIOContext **pb)
{
    if (*ifmt)
        avformat_close_input(ifmt);
    fd_io_close(pb);
}

/* ---- probe ------------------------------------------------------------ */

static void session_init(Session *s, JNIEnv *env)
{
    memset(s, 0, sizeof(*s));
    s->env = env;
    s->out_fd = -1;
    tls_session = s;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_konan_mov2mp4_engine_Remux_nativeProbe(JNIEnv *env, jclass clazz, jint fd)
{
    Session s;
    AVFormatContext *ifmt = NULL;
    AVIOContext *in_pb = NULL;
    const AVOutputFormat *ofmt = av_guess_format("mp4", NULL, NULL);
    const AVDictionaryEntry *e = NULL;
    struct stat64 st;
    AVBPrint b;
    int64_t file_size;
    int ret, first = 1;
    (void)clazz;

    session_init(&s, env);
    av_bprint_init(&b, 2048, 1 << 20);

    if (!ofmt) {
        js_error(&b, "engine", 0, &s);
        goto end;
    }
    ret = open_input(&s, fd, &ifmt, &in_pb, 0);
    if (ret < 0 || !ifmt) {
        js_error(&b, "open_failed", ret, &s);
        goto end;
    }

    av_bprintf(&b, "{\"ok\":true");
    js_key_str(&b, "container", ifmt->iformat->name);
    if ((e = av_dict_get(ifmt->metadata, "major_brand", NULL, 0)))
        js_key_str(&b, "brand", e->value);
    av_bprintf(&b, ",\"durationUs\":%" PRId64,
               ifmt->duration != AV_NOPTS_VALUE ? ifmt->duration : (int64_t)0);
    av_bprintf(&b, ",\"bitRate\":%" PRId64, ifmt->bit_rate > 0 ? ifmt->bit_rate : (int64_t)0);
    file_size = fstat64(fd, &st) == 0 ? (int64_t)st.st_size : 0;
    av_bprintf(&b, ",\"size\":%" PRId64, file_size);
    av_bprintf(&b, ",\"chapters\":%u", ifmt->nb_chapters);

    av_bprintf(&b, ",\"metadata\":{");
    e = NULL;
    while ((e = av_dict_iterate(ifmt->metadata, e))) {
        if (strlen(e->value) > 512)
            continue;
        if (!first)
            av_bprint_chars(&b, ',', 1);
        first = 0;
        js_str(&b, e->key);
        av_bprint_chars(&b, ':', 1);
        js_str(&b, e->value);
    }
    av_bprintf(&b, "},\"streams\":[");
    for (unsigned i = 0; i < ifmt->nb_streams; i++) {
        if (i)
            av_bprint_chars(&b, ',', 1);
        describe_stream(&b, ofmt, ifmt->streams[i], file_size);
    }
    av_bprintf(&b, "]}");

end:
    close_input(&ifmt, &in_pb);
    tls_session = NULL;
    return to_bytes(env, &b);
}

/* ---- remux ------------------------------------------------------------ */

typedef struct StreamMap {
    int out;
    int64_t last_dts;
    int64_t delta_last;
    int64_t packets;
    int64_t bytes;
    int ts_fixed;
    int checked;               /* compared against the MP4 on read-back */
    int by_nal;                /* compare H.264 NAL units, not raw bytes (see below) */
    int64_t cmp_bytes;         /* bytes fed to the hash */
    struct AVMurMur3 *hash;    /* running hash of every payload byte copied */
} StreamMap;

/*
 * A few MOVs (AVC-Intra camera files) store H.264 with start codes. MP4 requires
 * length prefixes, so the muxer rewrites the 4-byte markers between NAL units
 * and leaves the units themselves alone. For those tracks the comparison hashes
 * the NAL units: same picture data, whichever way it is delimited.
 */
static int needs_nal_compare(const AVCodecParameters *par)
{
    return par->codec_id == AV_CODEC_ID_H264 && par->extradata_size > 0 && par->extradata[0] != 1;
}

/* First 00 00 01 that still has a byte after it; same rule as libavformat/nal.c. */
static const uint8_t *find_startcode(const uint8_t *p, const uint8_t *end)
{
    const uint8_t *q;
    for (q = p; q + 3 < end; q++) {
        if (q[0] == 0 && q[1] == 0 && q[2] == 1) {
            if (p < q && !q[-1])
                q--;
            return q;
        }
    }
    return end;
}

static void hash_annexb_nals(struct AVMurMur3 *hash, const uint8_t *buf, int size, int64_t *bytes)
{
    const uint8_t *end = buf + size;
    const uint8_t *nal_start = find_startcode(buf, end), *nal_end;

    for (;;) {
        while (nal_start < end && !*(nal_start++))
            ;
        if (nal_start == end)
            break;
        nal_end = find_startcode(nal_start, end);
        av_murmur3_update(hash, nal_start, nal_end - nal_start);
        *bytes += nal_end - nal_start;
        nal_start = nal_end;
    }
}

static void hash_sized_nals(struct AVMurMur3 *hash, const uint8_t *buf, int size, int length_size,
                            int64_t *bytes)
{
    const uint8_t *p = buf, *end = buf + size;

    while (end - p >= length_size) {
        uint32_t len = 0;
        for (int i = 0; i < length_size; i++)
            len = (len << 8) | *p++;
        if (len > (uint32_t)(end - p))
            len = (uint32_t)(end - p); /* damaged sample: take what is there */
        av_murmur3_update(hash, p, len);
        *bytes += len;
        p += len;
    }
}

static int64_t median3(int64_t a, int64_t b, int64_t c)
{
    if (a > b) {
        int64_t t = a;
        a = b;
        b = t;
    }
    if (b > c)
        b = c;
    return a > b ? a : b;
}

/* Same per-stream setup ffmpeg does for `-c copy`. */
static int setup_stream(AVFormatContext *ofmt, const AVStream *in, AVStream **result)
{
    const struct AVCodecTag *const *tags = ofmt->oformat->codec_tag;
    AVStream *out = avformat_new_stream(ofmt, NULL);
    AVCodecParameters *par;
    unsigned int default_tag;
    int ret;

    if (!out)
        return AVERROR(ENOMEM);
    par = out->codecpar;
    ret = avcodec_parameters_copy(par, in->codecpar);
    if (ret < 0)
        return ret;

    /* Keep the source sample-entry tag (hvc1, avc1, dvh1...) when MP4 allows it,
     * otherwise let the muxer pick its own. */
    if (tags && av_codec_get_id(tags, par->codec_tag) != par->codec_id &&
        av_codec_get_tag2(tags, par->codec_id, &default_tag))
        par->codec_tag = 0;

    out->time_base = in->time_base;
    out->avg_frame_rate = in->avg_frame_rate;
    out->r_frame_rate = in->r_frame_rate;
    out->disposition = in->disposition;

    if (par->codec_type == AVMEDIA_TYPE_AUDIO) {
        /* PCM in MP4 needs a named channel layout. QuickTime files often give
         * only a channel count; one or two channels can only mean mono or stereo. */
        if (par->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC && av_get_bits_per_sample(par->codec_id) > 0 &&
            (par->ch_layout.nb_channels == 1 || par->ch_layout.nb_channels == 2)) {
            int channels = par->ch_layout.nb_channels;
            av_channel_layout_uninit(&par->ch_layout);
            av_channel_layout_default(&par->ch_layout, channels);
        }
        if ((par->block_align == 1 || par->block_align == 1152 || par->block_align == 576) &&
            par->codec_id == AV_CODEC_ID_MP3)
            par->block_align = 0;
        if (par->codec_id == AV_CODEC_ID_AC3)
            par->block_align = 0;
    } else if (par->codec_type == AVMEDIA_TYPE_VIDEO) {
        AVRational sar = in->sample_aspect_ratio.num ? in->sample_aspect_ratio
                                                      : par->sample_aspect_ratio;
        out->sample_aspect_ratio = par->sample_aspect_ratio = sar;
    }

    ret = av_dict_copy(&out->metadata, in->metadata, 0);
    if (ret < 0)
        return ret;
    *result = out;
    return 0;
}

static int copy_chapters(AVFormatContext *ofmt, const AVFormatContext *ifmt)
{
    AVChapter **list;

    if (!ifmt->nb_chapters)
        return 0;
    list = av_calloc(ifmt->nb_chapters, sizeof(*list));
    if (!list)
        return AVERROR(ENOMEM);
    ofmt->chapters = list;
    for (unsigned i = 0; i < ifmt->nb_chapters; i++) {
        const AVChapter *in = ifmt->chapters[i];
        AVChapter *out = av_mallocz(sizeof(*out));
        if (!out)
            return AVERROR(ENOMEM);
        out->id = in->id;
        out->time_base = in->time_base;
        out->start = in->start;
        out->end = in->end;
        av_dict_copy(&out->metadata, in->metadata, 0);
        ofmt->chapters[ofmt->nb_chapters++] = out;
    }
    return 0;
}

static int has_apple_keys(const AVDictionary *meta)
{
    const AVDictionaryEntry *e = NULL;
    while ((e = av_dict_iterate(meta, e)))
        if (!strncmp(e->key, "com.apple.", 10))
            return 1;
    return 0;
}

/* Is this output track's payload expected to come back byte for byte? Subtitle
 * tracks get an extra end marker from the muxer and cover art is stored as a
 * tag, so only picture and sound are compared. */
static int is_checked(const AVStream *st)
{
    return (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO || st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) &&
           !(st->disposition & AV_DISPOSITION_ATTACHED_PIC);
}

/**
 * Read the finished MP4 back and compare each picture and sound track with what
 * was taken from the MOV: same format, same size, same number of bytes, same
 * hash of those bytes. Returns 0 when everything matches; otherwise a negative
 * value with a short reason in why.
 */
static int verify_output(Session *s, const AVFormatContext *ifmt, StreamMap *map, unsigned nb_map,
                         char *why, size_t why_size)
{
    AVFormatContext *vfmt = NULL;
    AVIOContext *vpb = NULL;
    AVPacket *pkt = NULL;
    struct AVMurMur3 **hash = NULL;
    int64_t *bytes = NULL, *packets = NULL;
    int *source = NULL, *track_of = NULL, *length_size = NULL;
    unsigned nb_checked = 0, seen = 0;
    int ret;

    why[0] = 0;
    for (unsigned i = 0; i < nb_map; i++)
        nb_checked += map[i].checked;
    if (!nb_checked)
        return 0;

    hash = av_calloc(nb_checked, sizeof(*hash));
    bytes = av_calloc(nb_checked, sizeof(*bytes));
    packets = av_calloc(nb_checked, sizeof(*packets));
    source = av_calloc(nb_checked, sizeof(*source));
    length_size = av_calloc(nb_checked, sizeof(*length_size));
    pkt = av_packet_alloc();
    if (!hash || !bytes || !packets || !source || !length_size || !pkt) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    /* Checked tracks come back in the order they were written. */
    for (unsigned k = 0; k < nb_checked; k++) {
        int best = -1;
        for (unsigned i = 0; i < nb_map; i++) {
            int taken = 0;
            if (!map[i].checked)
                continue;
            for (unsigned j = 0; j < k; j++)
                taken |= source[j] == (int)i;
            if (!taken && (best < 0 || map[i].out < map[best].out))
                best = (int)i;
        }
        source[k] = best;
        hash[k] = av_murmur3_alloc();
        if (!hash[k]) {
            ret = AVERROR(ENOMEM);
            goto end;
        }
        av_murmur3_init(hash[k]);
    }

    ret = open_input(s, s->out_fd, &vfmt, &vpb, 1);
    if (ret < 0 || !vfmt) {
        snprintf(why, why_size, "the saved MP4 could not be read back");
        ret = ret < 0 ? ret : AVERROR_INVALIDDATA;
        goto end;
    }
    track_of = av_calloc(vfmt->nb_streams ? vfmt->nb_streams : 1, sizeof(*track_of));
    if (!track_of) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    for (unsigned i = 0; i < vfmt->nb_streams; i++) {
        track_of[i] = -1;
        if (seen < nb_checked && is_checked(vfmt->streams[i]))
            track_of[i] = (int)seen++;
    }
    if (seen != nb_checked) {
        snprintf(why, why_size, "the MP4 has %u picture and sound tracks, expected %u", seen, nb_checked);
        ret = AVERROR_INVALIDDATA;
        goto end;
    }
    for (unsigned i = 0; i < vfmt->nb_streams; i++) {
        const AVCodecParameters *a, *b = vfmt->streams[i]->codecpar;
        if (track_of[i] < 0)
            continue;
        a = ifmt->streams[source[track_of[i]]]->codecpar;
        if (a->codec_id != b->codec_id) {
            snprintf(why, why_size, "track %d changed format", track_of[i] + 1);
            ret = AVERROR_INVALIDDATA;
            goto end;
        }
        if (a->codec_type == AVMEDIA_TYPE_VIDEO && (a->width != b->width || a->height != b->height)) {
            snprintf(why, why_size, "picture size changed from %dx%d to %dx%d", a->width, a->height,
                     b->width, b->height);
            ret = AVERROR_INVALIDDATA;
            goto end;
        }
        if (a->codec_type == AVMEDIA_TYPE_VIDEO) {
            /* Pixel shape (anamorphic video): must come back the same, within rounding. */
            AVRational sa = ifmt->streams[source[track_of[i]]]->sample_aspect_ratio;
            AVRational sb = vfmt->streams[i]->sample_aspect_ratio;
            double ra = sa.num > 0 && sa.den > 0 ? av_q2d(sa) : 1.0;
            double rb = sb.num > 0 && sb.den > 0 ? av_q2d(sb) : 1.0;
            if (ra / rb > 1.005 || rb / ra > 1.005) {
                snprintf(why, why_size, "pixel shape changed from %d:%d to %d:%d", sa.num, sa.den, sb.num, sb.den);
                ret = AVERROR_INVALIDDATA;
                goto end;
            }
        }
        if (a->codec_type == AVMEDIA_TYPE_AUDIO &&
            (a->sample_rate != b->sample_rate || a->ch_layout.nb_channels != b->ch_layout.nb_channels)) {
            snprintf(why, why_size, "sound layout changed");
            ret = AVERROR_INVALIDDATA;
            goto end;
        }
        if (map[source[track_of[i]]].by_nal) {
            if (b->extradata_size < 7 || b->extradata[0] != 1) {
                snprintf(why, why_size, "picture track %d has no H.264 configuration", track_of[i] + 1);
                ret = AVERROR_INVALIDDATA;
                goto end;
            }
            length_size[track_of[i]] = (b->extradata[4] & 3) + 1;
        }
    }

    while (!s->cancelled) {
        ret = av_read_frame(vfmt, pkt);
        if (ret == AVERROR(EAGAIN))
            continue;
        if (ret < 0)
            break;
        if ((unsigned)pkt->stream_index < vfmt->nb_streams && track_of[pkt->stream_index] >= 0) {
            int k = track_of[pkt->stream_index];
            if (length_size[k]) {
                hash_sized_nals(hash[k], pkt->data, pkt->size, length_size[k], &bytes[k]);
            } else {
                av_murmur3_update(hash[k], pkt->data, pkt->size);
                bytes[k] += pkt->size;
            }
            packets[k]++;
            s->done += pkt->size;
        }
        av_packet_unref(pkt);
        emit_progress(s, 0);
    }
    if (s->cancelled) {
        ret = AVERROR_EXIT;
        goto end;
    }
    if (ret != AVERROR_EOF) {
        snprintf(why, why_size, "the saved MP4 could not be read to its end");
        goto end;
    }
    ret = 0;
    for (unsigned k = 0; k < nb_checked; k++) {
        StreamMap *m = &map[source[k]];
        uint8_t wrote[16], read[16];
        int video = ifmt->streams[source[k]]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO;

        av_murmur3_final(m->hash, wrote);
        av_murmur3_final(hash[k], read);
        if (bytes[k] != m->cmp_bytes || memcmp(wrote, read, sizeof(wrote)) || (video && packets[k] != m->packets)) {
            snprintf(why, why_size,
                     "%s track %u differs after saving (%" PRId64 " bytes in %" PRId64 " samples copied, %"
                     PRId64 " bytes in %" PRId64 " read back)",
                     video ? "picture" : "sound", k + 1, m->cmp_bytes, m->packets, bytes[k], packets[k]);
            ret = AVERROR_INVALIDDATA;
            break;
        }
    }

end:
    av_packet_free(&pkt);
    close_input(&vfmt, &vpb);
    if (hash)
        for (unsigned k = 0; k < nb_checked; k++)
            av_free(hash[k]);
    av_free(hash);
    av_free(bytes);
    av_free(packets);
    av_free(source);
    av_free(track_of);
    av_free(length_size);
    return ret;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_konan_mov2mp4_engine_Remux_nativeRemux(JNIEnv *env, jclass clazz, jint in_fd, jint out_fd,
                                                jobject listener)
{
    Session s;
    AVFormatContext *ifmt = NULL, *ofmt = NULL;
    AVIOContext *in_pb = NULL, *out_pb = NULL;
    AVDictionary *opts = NULL;
    AVPacket *pkt = NULL;
    StreamMap *map = NULL;
    struct stat64 st;
    AVBPrint b;
    unsigned nb_map = 0;
    int ret, copied = 0, apple = 0, read_error = 0;
    int64_t fixed_total = 0, got = 0;
    char why[256] = "";
    (void)clazz;

    session_init(&s, env);
    s.out_fd = out_fd;
    av_bprint_init(&b, 2048, 1 << 20);
    if (listener) {
        jclass cls = (*env)->GetObjectClass(env, listener);
        s.listener = listener;
        s.on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(IJJ)Z");
        if (!s.on_progress) {
            (*env)->ExceptionClear(env);
            s.listener = NULL;
        }
    }

    ret = open_input(&s, in_fd, &ifmt, &in_pb, 0);
    if (ret < 0 || !ifmt) {
        js_error(&b, s.cancelled ? "cancelled" : "open_failed", ret, &s);
        goto end;
    }
    s.total = fstat64(in_fd, &st) == 0 ? (int64_t)st.st_size : 0;

    ret = avformat_alloc_output_context2(&ofmt, NULL, "mp4", NULL);
    if (ret < 0 || !ofmt) {
        js_error(&b, "engine", ret, &s);
        goto end;
    }
    out_pb = fd_io_open(out_fd, 1, &s);
    if (!out_pb) {
        js_error(&b, "engine", AVERROR(ENOMEM), &s);
        goto end;
    }
    ofmt->pb = out_pb;
    ofmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    ofmt->opaque = &s;
    ofmt->io_open = out_io_open;
    ofmt->io_close2 = out_io_close2;
    ofmt->interrupt_callback.callback = interrupt_cb;
    ofmt->interrupt_callback.opaque = &s;
    /* Needed for the Dolby Vision configuration box and stereo/spherical boxes. */
    ofmt->strict_std_compliance = FF_COMPLIANCE_UNOFFICIAL;

    nb_map = ifmt->nb_streams;
    map = av_calloc(nb_map ? nb_map : 1, sizeof(*map));
    if (!map) {
        js_error(&b, "engine", AVERROR(ENOMEM), &s);
        goto end;
    }
    for (unsigned i = 0; i < nb_map; i++) {
        const char *why;
        AVStream *out;

        map[i].out = -1;
        map[i].last_dts = AV_NOPTS_VALUE;
        map[i].delta_last = AV_NOPTS_VALUE;
        if (decide(ofmt->oformat, ifmt->streams[i], &why) != ACT_COPY)
            continue;
        ret = setup_stream(ofmt, ifmt->streams[i], &out);
        if (ret < 0) {
            js_error(&b, "engine", ret, &s);
            goto end;
        }
        map[i].out = out->index;
        map[i].checked = is_checked(out);
        map[i].by_nal = needs_nal_compare(ifmt->streams[i]->codecpar);
        map[i].hash = av_murmur3_alloc();
        if (!map[i].hash) {
            js_error(&b, "engine", AVERROR(ENOMEM), &s);
            goto end;
        }
        av_murmur3_init(map[i].hash);
        copied++;
    }
    if (!copied) {
        js_error(&b, "no_streams", 0, &s);
        goto end;
    }

    av_dict_copy(&ofmt->metadata, ifmt->metadata, 0);
    av_dict_set(&ofmt->metadata, "major_brand", NULL, 0);
    av_dict_set(&ofmt->metadata, "minor_version", NULL, 0);
    av_dict_set(&ofmt->metadata, "compatible_brands", NULL, 0);
    ret = copy_chapters(ofmt, ifmt);
    if (ret < 0) {
        js_error(&b, "engine", ret, &s);
        goto end;
    }

    /* iPhone files keep make, model, location and capture date in Apple's
     * key/value metadata. The default MP4 tag writer drops those, so write the
     * keys through unchanged when the source has them. */
    apple = has_apple_keys(ifmt->metadata);
    av_dict_set(&opts, "movflags", apple ? "+faststart+use_metadata_tags" : "+faststart", 0);

    ret = avformat_write_header(ofmt, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        js_error(&b, s.cancelled ? "cancelled" : "mux_init_failed", ret, &s);
        goto end;
    }

    pkt = av_packet_alloc();
    if (!pkt) {
        js_error(&b, "engine", AVERROR(ENOMEM), &s);
        goto end;
    }

    s.phase = PHASE_COPY;
    emit_progress(&s, 1);
    while (!s.cancelled) {
        StreamMap *m;
        AVStream *ist, *ost;

        ret = av_read_frame(ifmt, pkt);
        if (ret == AVERROR(EAGAIN))
            continue;
        if (ret < 0)
            break;
        if ((unsigned)pkt->stream_index >= nb_map || map[pkt->stream_index].out < 0) {
            av_packet_unref(pkt);
            continue;
        }
        m = &map[pkt->stream_index];
        ist = ifmt->streams[pkt->stream_index];
        ost = ofmt->streams[m->out];
        if (av_packet_get_side_data(pkt, AV_PKT_DATA_ENCRYPTION_INFO, NULL)) {
            /* Protected samples would be copied without their keys and never play. */
            av_packet_unref(pkt);
            js_error(&b, "encrypted", 0, &s);
            goto end;
        }
        m->packets++;
        m->bytes += pkt->size;
        s.done += pkt->size;
        if (m->by_nal) {
            hash_annexb_nals(m->hash, pkt->data, pkt->size, &m->cmp_bytes);
        } else {
            av_murmur3_update(m->hash, pkt->data, pkt->size);
            m->cmp_bytes += pkt->size;
        }

        if (av_cmp_q(ist->time_base, ost->time_base)) {
            if (ost->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && pkt->dts != AV_NOPTS_VALUE &&
                ost->codecpar->sample_rate > 0) {
                int duration = av_get_audio_frame_duration2(ost->codecpar, pkt->size);
                if (!duration)
                    duration = ost->codecpar->frame_size;
                pkt->dts = av_rescale_delta(ist->time_base, pkt->dts,
                                            (AVRational){1, ost->codecpar->sample_rate},
                                            duration > 0 ? duration : 0, &m->delta_last,
                                            ost->time_base);
                pkt->pts = pkt->dts;
                pkt->duration = av_rescale_q(pkt->duration, ist->time_base, ost->time_base);
            } else {
                av_packet_rescale_ts(pkt, ist->time_base, ost->time_base);
            }
        }

        /* The MP4 muxer refuses timestamps that go backwards. ffmpeg nudges
         * them forward by one tick and so do we; the count is reported. */
        if (pkt->dts != AV_NOPTS_VALUE && pkt->pts != AV_NOPTS_VALUE && pkt->dts > pkt->pts) {
            int64_t guess = m->last_dts == AV_NOPTS_VALUE
                                ? FFMIN(pkt->pts, pkt->dts)
                                : median3(pkt->pts, pkt->dts, m->last_dts + 1);
            pkt->pts = pkt->dts = guess;
            m->ts_fixed++;
        }
        if (pkt->dts != AV_NOPTS_VALUE && m->last_dts != AV_NOPTS_VALUE) {
            int64_t min_dts = m->last_dts + 1;
            if (pkt->dts < min_dts) {
                if (pkt->pts != AV_NOPTS_VALUE && pkt->pts >= pkt->dts)
                    pkt->pts = FFMAX(pkt->pts, min_dts);
                pkt->dts = min_dts;
                m->ts_fixed++;
            }
        }
        if (pkt->dts != AV_NOPTS_VALUE)
            m->last_dts = pkt->dts;

        pkt->stream_index = m->out;
        pkt->pos = -1;
        ret = av_interleaved_write_frame(ofmt, pkt);
        av_packet_unref(pkt);
        if (ret < 0) {
            js_error(&b, s.cancelled ? "cancelled" : s.io_errno == ENOSPC ? "no_space" : "write_failed",
                     ret, &s);
            goto end;
        }
        emit_progress(&s, 0);
    }
    if (s.cancelled) {
        js_error(&b, "cancelled", 0, &s);
        goto end;
    }
    for (unsigned i = 0; i < nb_map; i++)
        got += map[i].packets;
    if (!got) {
        /* A header with no picture or sound behind it. */
        js_error(&b, "no_data", ret != AVERROR_EOF ? ret : 0, &s);
        goto end;
    }
    /* A damaged or cut-off source: keep what could be read and report it. */
    if (ret < 0 && ret != AVERROR_EOF)
        read_error = ret;

    /* Finish: write the index and move it to the front of the file. */
    avio_flush(ofmt->pb);
    s.phase = PHASE_FINISH;
    s.done = 0;
    s.total = fstat64(out_fd, &st) == 0 ? (int64_t)st.st_size : s.total;
    emit_progress(&s, 1);
    ret = av_write_trailer(ofmt);
    if (ret >= 0) {
        avio_flush(ofmt->pb);
        if (ofmt->pb->error < 0)
            ret = ofmt->pb->error;
    }
    if (s.cancelled) {
        js_error(&b, "cancelled", 0, &s);
        goto end;
    }
    if (ret < 0) {
        js_error(&b, s.io_errno == ENOSPC ? "no_space" : "write_failed", ret, &s);
        goto end;
    }

    /* Prove it: read the MP4 back and compare it with what was copied. */
    s.phase = PHASE_VERIFY;
    s.done = 0;
    s.total = fstat64(out_fd, &st) == 0 ? (int64_t)st.st_size : s.total;
    emit_progress(&s, 1);
    ret = verify_output(&s, ifmt, map, nb_map, why, sizeof(why));
    if (s.cancelled) {
        js_error(&b, "cancelled", 0, &s);
        goto end;
    }
    if (ret < 0) {
        av_strlcpy(s.log, why[0] ? why : "the saved MP4 does not match the MOV", sizeof(s.log));
        js_error(&b, "verify_failed", 0, &s);
        goto end;
    }

    av_bprintf(&b, "{\"ok\":true,\"outSize\":%" PRId64,
               fstat64(out_fd, &st) == 0 ? (int64_t)st.st_size : (int64_t)0);
    av_bprintf(&b, ",\"appleMetadata\":%s,\"streams\":[", apple ? "true" : "false");
    for (unsigned i = 0, first = 1; i < nb_map; i++) {
        if (map[i].out < 0)
            continue;
        if (!first)
            av_bprint_chars(&b, ',', 1);
        first = 0;
        fixed_total += map[i].ts_fixed;
        av_bprintf(&b,
                   "{\"in\":%u,\"out\":%d,\"type\":\"%s\",\"packets\":%" PRId64 ",\"bytes\":%" PRId64
                   ",\"tsFixed\":%d}",
                   i, map[i].out, type_name(ifmt->streams[i]->codecpar->codec_type), map[i].packets,
                   map[i].bytes, map[i].ts_fixed);
    }
    av_bprintf(&b, "],\"tsFixed\":%" PRId64, fixed_total);
    if (read_error) {
        char msg[AV_ERROR_MAX_STRING_SIZE] = "";
        av_strerror(read_error, msg, sizeof(msg));
        js_key_str(&b, "readError", msg);
    }
    av_bprint_chars(&b, '}', 1);

end:
    av_packet_free(&pkt);
    av_dict_free(&opts);
    if (ofmt) {
        ofmt->pb = NULL;
        avformat_free_context(ofmt);
    }
    fd_io_close(&out_pb);
    close_input(&ifmt, &in_pb);
    if (map)
        for (unsigned i = 0; i < nb_map; i++)
            av_free(map[i].hash);
    av_free(map);
    tls_session = NULL;
    return to_bytes(env, &b);
}

JNIEXPORT jstring JNICALL
Java_dev_konan_mov2mp4_engine_Remux_nativeVersion(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    return (*env)->NewStringUTF(env, av_version_info());
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    av_log_set_level(AV_LOG_WARNING);
    av_log_set_callback(log_callback);
    return JNI_VERSION_1_6;
}
