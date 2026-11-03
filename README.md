# MOV to MP4

Android app that turns a `.mov` into an `.mp4` without re-encoding. Picture and sound are copied
packet by packet from the QuickTime wrapper into an MP4 wrapper, then the MP4 is read back and
compared with what was copied. If MP4 cannot hold a format (ProRes, for example) the app says so and
saves nothing.

## How it is used

- Open a `.mov` from another app (WhatsApp document, Files, Gmail, Drive) and pick **Convert to MP4**.
- Or share one or more videos to the app, or pick them inside it.
- The MP4 lands in `Movies/MOV to MP4` and shows in the gallery. **Share** sends it on, **Play** opens it.

## Layout

| Path | What |
| --- | --- |
| `native/remux.c` | The engine: libavformat stream copy, read-back check, JSON results over JNI |
| `native/build.sh` | Cross-builds FFmpeg 8.1.3 (MOV demuxer, MP4 muxer, parsers; no codecs) and `libmovmp4.so` |
| `app/src/main/java/dev/konan/mov2mp4/engine` | Kotlin wrapper and plain-language labels |
| `app/src/main/java/dev/konan/mov2mp4/data` | The queue (`Converter`) and the rules for what can be converted (`Plan`) |
| `app/src/main/java/dev/konan/mov2mp4/ui` | Compose screens |
| `ConvertService.kt` | Foreground service so long files finish in the background |

## Build

```
native/build.sh                 # needs the NDK (r28+) and an unpacked FFmpeg 8.1.x tree; writes app/src/main/jniLibs
./gradlew :app:assembleRelease  # JDK 17; set ANDROID_HOME or sdk.dir in local.properties
```

The release build is signed with the key named in `keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) when that file exists, and with the debug key otherwise. Neither file is in
the repository. Keep your key: an update only installs over the old app when it is signed with the same key.

## What "lossless" covers

- Every video, audio and text-subtitle track MP4 can hold is copied; the bytes of each picture and
  sound track are hashed on the way in and again when the finished MP4 is read back.
- Kept: rotation, HDR colour tags, Dolby Vision configuration, spatial (MV-HEVC) layers, chapters,
  timecode, creation date, and Apple's make / model / location keys.
- Not carried: Apple's timed-metadata tracks (`mebx`), which hold things like Live Photo markers.
- Samples an edit list hides are left out, as in `ffmpeg -c copy`: what plays is what is copied.

FFmpeg is used under the LGPL 2.1 or later.
