#!/bin/bash
# Builds the Azeroth Survivors app's native helpers for arm64 Android, into azeroth/src/main/jniLibs/arm64-v8a:
#   libazerothmpq.so  StormLib (MIT) + mpq_jni.cpp, read by app.azerothsurvivors.Mpq
#   libbsdtar.so      libarchive's bsdtar (BSD), run as a program to unpack the Windows download's .rar
#
# Sources, in one folder:
#   git clone --depth 1 https://github.com/ladislav-zezula/StormLib                 (116135c, 2026-10-12)
#   git clone --depth 1 --branch v3.7.7 https://github.com/libarchive/libarchive
#
# Usage: tools/azeroth/build_native.sh <sources> <build dir>   (needs the NDK in app/build.gradle.kts)
set -euo pipefail
SRC=$1
OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
SDK=$(grep sdk.dir "$REPO/local.properties" | cut -d= -f2)
NDK=$SDK/ndk/27.3.13750724
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=31
LIBS=$REPO/azeroth/src/main/jniLibs/arm64-v8a
mkdir -p "$OUT" "$LIBS"
CMAKE_ANDROID=(-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_static -G Ninja)

echo "== StormLib"
cmake -S "$SRC/StormLib" -B "$OUT/storm" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=OFF -DSTORM_SKIP_INSTALL=ON \
  -DSTORM_USE_BUNDLED_LIBRARIES=ON -DSTORM_BUILD_TESTS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON > "$OUT/storm-config.log"
cmake --build "$OUT/storm" > "$OUT/storm-build.log" 2>&1 || { tail -30 "$OUT/storm-build.log"; exit 1; }
STORM_A=$(find "$OUT/storm" -name "libstorm.a" | head -1)
"$TC/bin/aarch64-linux-android$API-clang++" -O2 -fPIC -shared -static-libstdc++ -I"$SRC/StormLib/src" \
  "$HERE/mpq_jni.cpp" "$STORM_A" -lz -Wl,--no-undefined -Wl,-z,max-page-size=16384 -o "$LIBS/libazerothmpq.so"
"$TC/bin/llvm-strip" "$LIBS/libazerothmpq.so"

echo "== bsdtar"
cmake -S "$SRC/libarchive" -B "$OUT/archive" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=OFF \
  -DENABLE_TAR=ON -DENABLE_TAR_SHARED=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF -DENABLE_TEST=OFF \
  -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_LIBB2=OFF -DENABLE_LZ4=OFF -DENABLE_LZO=OFF \
  -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_BZip2=OFF -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_PCREPOSIX=OFF \
  -DENABLE_PCRE2POSIX=OFF -DENABLE_CNG=OFF -DENABLE_XATTR=OFF -DENABLE_ACL=OFF -DENABLE_ICONV=OFF -DENABLE_WERROR=OFF \
  > "$OUT/archive-config.log" 2>&1 || { tail -30 "$OUT/archive-config.log"; exit 1; }
cmake --build "$OUT/archive" --target bsdtar > "$OUT/archive-build.log" 2>&1 || { tail -40 "$OUT/archive-build.log"; exit 1; }
cp "$(find "$OUT/archive" -name bsdtar -type f | head -1)" "$LIBS/libbsdtar.so"
"$TC/bin/llvm-strip" "$LIBS/libbsdtar.so"

file "$LIBS"/*.so
ls -la "$LIBS"
