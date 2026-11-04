# mov-to-mp4

An Android app that turns a `.mov` from an iPhone or a camera into an `.mp4` on the phone, without re-encoding.

![A terminal builds the release APK with Gradle and lists the FFmpeg-based libmovmp4.so packed in it for three Android ABIs](docs/media/hero.gif)

## Why

The H.264 and HEVC video inside an iPhone `.mov` fits in an MP4 as it is. Re-encoding it costs time, battery and quality and gains nothing. This app copies every packet from the QuickTime container into an MP4 container, as `ffmpeg -c copy` does, and then reads the MP4 back and compares each track's bytes with what it copied. When MP4 cannot hold a track's format (ProRes, for example), the app says so and saves nothing, instead of re-encoding.

## Install

There is no published APK. Build it yourself.

Requirements:

- JDK 17 (tested with OpenJDK 17.0.20.1).
- The Android SDK with platform 35. Set `ANDROID_HOME`, or `sdk.dir` in `local.properties`.
- An Android 10 (API 29) or later device for the result.

The Gradle 8.12 wrapper, Android Gradle Plugin 8.10.1 and Kotlin 2.1.21 come with the build files.

```bash
git clone <this repo> mov-to-mp4 && cd mov-to-mp4
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Install it on the phone.

Without `keystore.properties`, the release build is signed with your debug key. To sign with your own key, create `keystore.properties` in the repository root (see [Configuration](#configuration)). Keep that key. Android installs an update over the old app only when both are signed with the same key.

### Rebuild the FFmpeg library

The repository ships prebuilt `app/src/main/jniLibs/<abi>/libmovmp4.so` files, so the APK builds without the NDK. To build them from source, for example to link a modified FFmpeg as the LGPL allows, you need the Android NDK and the FFmpeg 8.1.x source:

```bash
curl -LO https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz
tar xf ffmpeg-8.1.3.tar.xz
FF_SRC=$PWD/ffmpeg-8.1.3 NDK=$ANDROID_HOME/ndk/27.1.12297006 ABIS=arm64-v8a native/build.sh
./gradlew :app:assembleRelease
```

`native/build.sh` configures and builds FFmpeg once per ABI into `WORK`, then compiles `native/remux.c` against it and writes `app/src/main/jniLibs/<abi>/libmovmp4.so`. On this machine the arm64-v8a build took 21 seconds and wrote a 1,908,640-byte `libmovmp4.so`. The APK built after it carried the new library.

The script asks for NDK r28 or later so the libraries are 16 KB page aligned. It also passes `-Wl,-z,max-page-size=16384` to the linker, and the run above used NDK 27.1.12297006.

## Quick start

```console
$ ./gradlew :app:assembleRelease
...
> Task :app:minifyReleaseWithR8
> Task :app:packageRelease
> Task :app:assembleRelease

BUILD SUCCESSFUL in 1m 30s
45 actionable tasks: 41 executed, 4 from cache
$ unzip -l app/build/outputs/apk/release/app-release.apk | grep libmovmp4
  1792496  1981-01-01 01:01   lib/arm64-v8a/libmovmp4.so
  1761608  1981-01-01 01:01   lib/armeabi-v7a/libmovmp4.so
  1929792  1981-01-01 01:01   lib/x86_64/libmovmp4.so
```

On the phone there are three ways in:

- Open a `.mov` from another app (WhatsApp, Files, Gmail, Drive) and pick **Convert to MP4**.
- Share one or more videos to the app.
- Pick videos inside the app.

The MP4 is saved to `Movies/MOV to MP4` and shows in the gallery. **Share** sends it on and **Play** opens it.

## How it works

![An Open with or Share intent reaches Converter, which plans the job, keeps ConvertService in the foreground and hands file descriptors through Remux.kt to libmovmp4.so; the MP4 lands in MediaStore](docs/media/diagram.png)

`MainActivity` accepts `VIEW` intents for `video/*`, the QuickTime MIME types and file names ending in `.mov`, `.MOV` or `.qt`, and `SEND` and `SEND_MULTIPLE` from the share sheet. The manifest declares media capabilities so that Android hands over the original HEVC or HDR bytes instead of a transcoded copy.

`Converter` queues the jobs and starts `ConvertService`, a foreground service, so a long file keeps converting in the background. Before it writes anything, `Plan.of` checks the probe result. A file that is already an MP4 and not named `.mov`, an encrypted track, or a picture format MP4 cannot hold stops the job with a plain message. When only the sound cannot be carried, the app offers to save the picture without it. The job also stops when free space is less than the file size plus 32 MB.

`Converter` creates the output through `MediaStore` in `Movies/MOV to MP4` with `IS_PENDING` set, and passes the input and output file descriptors to `Remux.kt`. A `content://` document is never copied to a path first. `native/remux.c` runs in three phases:

1. Copy. libavformat reads every packet from the MOV demuxer and writes it untouched to the MP4 muxer. The stream setup follows `ffmpeg -c copy`, except that every video, audio and subtitle track is carried and the creation time is kept. Each picture and sound track is hashed with MurmurHash3 on the way in.
2. Finish. The muxer writes the MP4 index.
3. Verify. The MP4 is read back and each track is hashed again. If a track's byte count, hash or video sample count differs, the job fails with the numbers.

Only then does `Converter` clear `IS_PENDING`, so the MP4 appears in the gallery.

What the copy carries over:

- The copy keeps rotation, HDR colour tags, Dolby Vision configuration, spatial (MV-HEVC) layers, chapters, timecode, the creation date, and Apple's make, model and location keys.
- It drops Apple's timed-metadata tracks (`mebx`), which hold things like Live Photo markers.
- Samples that an edit list hides are left out, as with `ffmpeg -c copy`. What plays is what is copied.

## Configuration

The app has no settings file. These options control the build.

| Option | Default | What it does |
| --- | --- | --- |
| `keystore.properties` `storeFile` | none | Path to the release keystore, relative to the repository root. Without it, the release build uses the debug key |
| `keystore.properties` `storePassword` | none | Keystore password |
| `keystore.properties` `keyAlias` | none | Key alias |
| `keystore.properties` `keyPassword` | none | Key password |
| `-PonlyAbi=<abi>` | all three ABIs | Build an APK with one ABI, for example `arm64-v8a` |
| `FF_SRC` | `$HOME/mov2mp4-build/ffmpeg-8.1.3` | `native/build.sh`: the unpacked FFmpeg source tree |
| `NDK` | `$HOME/android_sdk/ndk/29.0.14206865` | `native/build.sh`: the Android NDK root |
| `WORK` | `$HOME/mov2mp4-build/out` | `native/build.sh`: scratch directory for the FFmpeg build |
| `ABIS` | `arm64-v8a armeabi-v7a x86_64` | `native/build.sh`: ABIs to build |
| `JOBS` | `nproc` | `native/build.sh`: parallel `make` jobs |

## Limits

- This copy of the app has not been run on a device or an emulator. The build, the APK contents and the FFmpeg rebuild were checked. The conversion itself was not.
- MOV in, MP4 out, nothing else. The app does not re-encode, trim or resize. A track that MP4 cannot hold, such as ProRes video, stops the conversion.
- Android 10 (API 29) or later.
- A library rebuilt from source is not byte-identical to the committed one. The arm64-v8a rebuild above, with NDK 27.1, is 1,908,640 bytes. The committed file is 1,792,496 bytes.

## License

The app's own code is MIT. See [LICENSE](LICENSE). It links FFmpeg 8.1.3, built LGPL-only (no `--enable-gpl`, no `--enable-nonfree`, no codecs), under the LGPL 2.1 or later. [NOTICE](NOTICE) lists what is linked and your rights under the LGPL. Anyone who distributes a binary built from this repository must pass on NOTICE and the FFmpeg source.
