#!/usr/bin/env bash
# Builds libmovmp4.so for each Android ABI: a static, demux/mux-only FFmpeg
# (QuickTime demuxer + MP4 muxer, no codecs) linked with remux.c.
#
#   FF_SRC   unpacked FFmpeg source tree (8.1.x)
#   NDK      Android NDK root (r28 or newer, so libraries are 16 KB aligned)
#   WORK     scratch directory for object files
#   ABIS     space-separated ABI list
#
# Output: app/src/main/jniLibs/<abi>/libmovmp4.so
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
FF_SRC=${FF_SRC:-$HOME/mov2mp4-build/ffmpeg-8.1.3}
NDK=${NDK:-$HOME/android_sdk/ndk/29.0.14206865}
WORK=${WORK:-$HOME/mov2mp4-build/out}
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}
API=29
JOBS=${JOBS:-$(nproc)}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64

for ABI in $ABIS; do
  case $ABI in
    arm64-v8a)   ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) ARCH=arm;     CPU=armv7-a; TRIPLE=armv7a-linux-androideabi ;;
    x86_64)      ARCH=x86_64;  CPU=x86-64;  TRIPLE=x86_64-linux-android ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  CC=$TC/bin/${TRIPLE}${API}-clang
  PREFIX=$WORK/$ABI
  BUILD=$WORK/build-$ABI

  if [ ! -f "$PREFIX/lib/libavformat.a" ]; then
    mkdir -p "$BUILD"
    (
      cd "$BUILD"
      "$FF_SRC/configure" \
        --prefix="$PREFIX" \
        --enable-cross-compile --target-os=android --arch=$ARCH --cpu=$CPU \
        --cc="$CC" --cxx="$CC++" --ld="$CC" \
        --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" \
        --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
        --sysroot="$TC/sysroot" \
        --enable-static --disable-shared --enable-pic \
        --disable-programs --disable-doc --disable-debug \
        --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample \
        --disable-network --disable-autodetect --disable-asm \
        --disable-everything \
        --enable-zlib \
        --enable-demuxer=mov \
        --enable-muxer=mp4 \
        --enable-parsers \
        --enable-bsf=aac_adtstoasc,vp9_superframe,extract_extradata \
        --extra-cflags="-fvisibility=hidden -ffunction-sections -fdata-sections" \
        > configure.log
      make -j"$JOBS" > make.log 2>&1
      make install > install.log 2>&1
    )
  fi

  OUT=$ROOT/app/src/main/jniLibs/$ABI
  mkdir -p "$OUT"
  "$CC" -shared -fPIC -O2 -Wall -Wextra -fvisibility=hidden \
    -ffunction-sections -fdata-sections \
    -I"$PREFIX/include" "$HERE/remux.c" -o "$OUT/libmovmp4.so" \
    -L"$PREFIX/lib" -lavformat -lavcodec -lavutil -lz -lm -llog \
    -Wl,--gc-sections -Wl,-Bsymbolic -Wl,--exclude-libs,ALL \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,-z,defs
  "$TC/bin/llvm-strip" --strip-unneeded "$OUT/libmovmp4.so"
  ls -l "$OUT/libmovmp4.so"
done
