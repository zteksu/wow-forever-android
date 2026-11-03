#!/bin/bash
# Builds innoextract as an arm64 Android executable (app/src/skyrim/jniLibs/arm64-v8a/libinnoextract.so),
# with bzip2, liblzma and Boost linked in statically. It only needs Android's libc, libm and libdl.
#
# Sources, in one folder:
#   git clone --depth 1 https://github.com/dscharrer/innoextract              (6e9e34e, 2025-02-06: Inno Setup 6.x)
#   git clone --depth 1 --branch v5.6.3 https://github.com/tukaani-project/xz
#   https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.bz2
#   https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz                     (sha256 ab5a0317...a2269)
#
# Usage: tools/innoextract/build.sh <sources> <build dir>   (needs the NDK in app/build.gradle.kts)
set -euo pipefail
SRC=$1        # folder with innoextract/, xz/, boost_1_86_0/, bzip2-1.0.8/
OUT=$2        # build + install prefix
SDK=$(grep sdk.dir "$(dirname "$0")/../../local.properties" | cut -d= -f2)
NDK=$SDK/ndk/27.3.13750724
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=26
CC=$TC/bin/aarch64-linux-android$API-clang
CXX=$TC/bin/aarch64-linux-android$API-clang++
AR=$TC/bin/llvm-ar
RANLIB=$TC/bin/llvm-ranlib
PREFIX=$OUT/prefix
mkdir -p $PREFIX/lib $PREFIX/include $OUT/work
CMAKE_ANDROID=(-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release -G Ninja)

echo "== bzip2"
cd $SRC/bzip2-1.0.8
for f in blocksort huffman crctable randtable compress decompress bzlib; do
  $CC -O2 -fPIC -D_FILE_OFFSET_BITS=64 -c $f.c -o $OUT/work/bz_$f.o
done
$AR rcs $PREFIX/lib/libbz2.a $OUT/work/bz_*.o
cp bzlib.h $PREFIX/include/

echo "== liblzma"
cmake -S $SRC/xz -B $OUT/work/xz "${CMAKE_ANDROID[@]}" -DCMAKE_INSTALL_PREFIX=$PREFIX -DBUILD_SHARED_LIBS=OFF \
  -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF \
  -DXZ_DOC=OFF -DXZ_NLS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON > $OUT/work/xz-config.log
cmake --build $OUT/work/xz > $OUT/work/xz-build.log
cmake --install $OUT/work/xz > /dev/null

echo "== boost"
cd $SRC/boost_1_86_0
[ -x ./b2 ] || ./bootstrap.sh --with-libraries=iostreams,filesystem,date_time,system,program_options > $OUT/work/boost-bootstrap.log
cat > $OUT/work/user-config.jam <<EOF
using clang : android : $CXX : <archiver>$AR <ranlib>$RANLIB ;
EOF
SYSROOT=$TC/sysroot
./b2 -q -j4 --user-config=$OUT/work/user-config.jam --build-dir=$OUT/work/boost-build --prefix=$PREFIX \
  toolset=clang-android target-os=android architecture=arm address-model=64 binary-format=elf abi=aapcs \
  link=static runtime-link=shared threading=multi variant=release cxxflags="-fPIC -std=c++17" \
  --with-iostreams --with-filesystem --with-date_time --with-system --with-program_options \
  -sNO_LZMA=1 -sNO_ZSTD=1 -sBZIP2_INCLUDE=$PREFIX/include -sBZIP2_LIBPATH=$PREFIX/lib \
  -sZLIB_INCLUDE=$SYSROOT/usr/include -sZLIB_LIBPATH=$SYSROOT/usr/lib/aarch64-linux-android/$API \
  install > $OUT/work/boost-build.log 2>&1 || { tail -40 $OUT/work/boost-build.log; exit 1; }

echo "== innoextract"
cmake -S $SRC/innoextract -B $OUT/work/inno "${CMAKE_ANDROID[@]}" \
  -DCMAKE_FIND_ROOT_PATH=$PREFIX -DBOOST_ROOT=$PREFIX -DBoost_NO_SYSTEM_PATHS=ON -DBoost_USE_STATIC_LIBS=ON \
  -DUSE_STATIC_LIBS=ON -DUSE_LTO=OFF -DWITH_CONV=builtin -DUSE_DYNAMIC_UTIMENSAT=OFF \
  -DLZMA_INCLUDE_DIR=$PREFIX/include -DLZMA_LIBRARY=$PREFIX/lib/liblzma.a \
  -DBZIP2_INCLUDE_DIR=$PREFIX/include -DBZIP2_LIBRARIES=$PREFIX/lib/libbz2.a -DBZIP2_LIBRARY=$PREFIX/lib/libbz2.a \
  > $OUT/work/inno-config.log 2>&1 || { tail -40 $OUT/work/inno-config.log; exit 1; }
cmake --build $OUT/work/inno > $OUT/work/inno-build.log 2>&1 || { tail -40 $OUT/work/inno-build.log; exit 1; }
$TC/bin/llvm-strip $OUT/work/inno/innoextract
cp $OUT/work/inno/innoextract $OUT/innoextract
file $OUT/innoextract
ls -la $OUT/innoextract
