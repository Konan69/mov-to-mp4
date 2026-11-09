# Building mov-to-mp4

The release build reads `keystore.properties` from the repository root. Keep that key: Android installs an update only when it is signed with the same key.

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
## Rebuild the FFmpeg library

The repository ships prebuilt `app/src/main/jniLibs/<abi>/libmovmp4.so` files, so the APK builds without the NDK. To build them from source, for example to link a modified FFmpeg as the LGPL allows, you need the Android NDK and the FFmpeg 8.1.x source:

```bash
curl -LO https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz
tar xf ffmpeg-8.1.3.tar.xz
FF_SRC=$PWD/ffmpeg-8.1.3 NDK=$ANDROID_HOME/ndk/27.1.12297006 ABIS=arm64-v8a native/build.sh
./gradlew :app:assembleRelease
```

`native/build.sh` configures and builds FFmpeg once per ABI into `WORK`, then compiles `native/remux.c` against it and writes `app/src/main/jniLibs/<abi>/libmovmp4.so`. On this machine the arm64-v8a build took 21 seconds and wrote a 1,908,640-byte `libmovmp4.so`. The APK built after it carried the new library.

The script asks for NDK r28 or later so the libraries are 16 KB page aligned. It also passes `-Wl,-z,max-page-size=16384` to the linker, and the run above used NDK 27.1.12297006.

