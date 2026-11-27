#!/usr/bin/env bash
# Build the zstd shared library from the vendored submodule using `zig cc`
# and install it into the caller's resources directory so Maven bundles it
# in the native JAR.
#
# zstd is pure C with no build-system dependencies, so we compile the library
# sources directly with zig cc — no autotools, no CMake, fully hermetic.
# Zig bundles clang + libc for every target, enabling cross-compilation
# without a sysroot or system toolchain.
#
# Usage:
#   ./scripts/build-zstd.sh <output-resources-dir> <target-classifier>
#
# target-classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "Usage: $0 <output-resources-dir> <target-classifier>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ZSTD_LIB="$PROJECT_DIR/third_party/zstd/lib"
mkdir -p "$1"
OUTPUT_RESOURCES="$(cd "$1" && pwd)"
CLASSIFIER="$2"
JOBS="${ZSTD_BUILD_JOBS:-$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)}"

# ---------------------------------------------------------------------------
# Map classifier -> (zig target triple, library name)
# ---------------------------------------------------------------------------
case "$CLASSIFIER" in
    osx-aarch64)     ZIG_TARGET="aarch64-macos";       LIB_NAME="libzstd.dylib" ;;
    osx-x86_64)      ZIG_TARGET="x86_64-macos";        LIB_NAME="libzstd.dylib" ;;
    linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu";    LIB_NAME="libzstd.so"    ;;
    linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu";   LIB_NAME="libzstd.so"    ;;
    windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu";  LIB_NAME="libzstd.dll"   ;;
    windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"; LIB_NAME="libzstd.dll"   ;;
    *) echo "Unsupported classifier: $CLASSIFIER" >&2; exit 1 ;;
esac

DEST_DIR="$OUTPUT_RESOURCES/native/$CLASSIFIER"
mkdir -p "$DEST_DIR"

# Skip if already built (CI cache or repeated local builds)
if [ -f "$DEST_DIR/$LIB_NAME" ]; then
    echo "[build-zstd] $DEST_DIR/$LIB_NAME already exists, skipping build."
    exit 0
fi

HOST_OS=$(uname -s); HOST_ARCH=$(uname -m)
# Git Bash's uname -s reports MINGW64_NT-.../MSYS_NT-... (never "Windows"), and
# an unmatched case leaves HOST_OS_NAME unset - a hard failure under set -u.
# HOST_CLASSIFIER is cosmetic (the "(cross from ...)" log hint only; the build
# itself is driven entirely by $ZIG_TARGET/$CLASSIFIER from argv), but it still
# needs a value on every host, including ones we don't specifically recognize.
case "$HOST_OS" in
    Darwin) HOST_OS_NAME="osx" ;;
    Linux) HOST_OS_NAME="linux" ;;
    MINGW*|MSYS*|CYGWIN*) HOST_OS_NAME="windows" ;;
    *) HOST_OS_NAME="unknown" ;;
esac
case "$HOST_ARCH" in
    arm64|aarch64) HOST_ARCH_NAME="aarch64" ;;
    x86_64) HOST_ARCH_NAME="x86_64" ;;
    *) HOST_ARCH_NAME="unknown" ;;
esac
HOST_CLASSIFIER="${HOST_OS_NAME}-${HOST_ARCH_NAME}"
CROSS=""
[ "$CLASSIFIER" != "$HOST_CLASSIFIER" ] && CROSS=" (cross from $HOST_CLASSIFIER)"

echo "[build-zstd] Building zstd $CLASSIFIER$CROSS with zig cc (jobs=$JOBS)..."

# Library sources: core (common/compress/decompress) plus the dictionary
# builder (ZDICT_* API). legacy/ (pre-1.0 frame decoders) is left out, like
# upstream 1.6.0 and zstd-jni since 1.5.7-12; so is deprecated/ (the old ZBUFF_*
# streaming wrapper).
# huf_decompress_amd64.S is hand-written BMI2 Huffman-decode asm; its body is
# entirely wrapped in `#if ZSTD_ENABLE_ASM_X86_64_BMI2` (gated on __x86_64__),
# so it compiles to a no-op object on every non-x86_64 target and only kicks
# in there via zstd's own runtime BMI2 detection (DYNAMIC_BMI2) — safe to
# always include rather than special-case it per classifier.
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
SRCS="$SRCS
$ZSTD_LIB/decompress/huf_decompress_amd64.S"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Compile flags:
#   -DZSTD_MULTITHREAD=1 -> multithreaded compression (ZSTD_c_nbWorkers), per
#                           ADR 0015. Unix targets get pthreads from zig's
#                           bundled libc; Windows uses zstd's native Win32
#                           threading (threading.h wraps _beginthreadex /
#                           CRITICAL_SECTION), so no winpthreads anywhere - the
#                           DLL imports stay UCRT + KERNEL32 only. On linux-gnu
#                           zig records DT_NEEDED libpthread.so.0 with pre-2.34
#                           versioned symbols (GLIBC_2.2.5/2.3.2): present on
#                           every glibc distro back to RHEL 8/Ubuntu 20.04/AL2,
#                           and irrelevant to the musl smoke legs, which are
#                           expected-fail until musl natives ship regardless.
#   -DXXH_NAMESPACE      -> matches zstd's own build, avoids xxhash symbol clashes
# ELF/Mach-O: -fvisibility=hidden + zstd's ZSTDLIB_VISIBLE keeps the surface
# minimal. Windows/MinGW: -fvisibility=hidden stays on too (it governs whether
# lld auto-exports undecorated globals on PE, same as ELF/Mach-O) and
# -DZSTD_DLL_EXPORT=1 flips zstd.h's ZSTDLIB_API to __declspec(dllexport) for
# just the public API - the PE analogue of ZSTDLIB_VISIBLE, giving the same
# tight export set on all three platforms instead of dumping every internal
# symbol (FSE_*, HUF_*, COVER_*, ...) into the DLL's export table.
VIS_FLAG="-fvisibility=hidden"
DLL_EXPORT_FLAG=""
LINK_EXTRA=""
# Strip the symbol/debug tables at link time. An unstripped ELF .so carries full
# debug_info and is ~6x larger than needed (4.0M -> ~250K); -s drops it. PE/COFF
# keeps its exports in the export table (separate from the symbol table), but
# lld still emits a multi-megabyte .pdb and an import .lib next to the .dll —
# those are deleted after the link below rather than suppressed via strip.
STRIP_FLAG="-s"
case "$CLASSIFIER" in
    windows-*) DLL_EXPORT_FLAG="-DZSTD_DLL_EXPORT=1"; STRIP_FLAG="" ;;
    # Full RELRO + immediate binding: GOT is remapped read-only after startup
    # relocation, closing off the classic GOT-overwrite exploit primitive.
    # ELF-only (-z is a GNU ld/lld ELF flag; Mach-O/PE have no equivalent).
    linux-*) LINK_EXTRA="-Wl,-z,relro,-z,now" ;;
esac

# Modern ARM baseline: ARMv8-A + CRC (zig's -mcpu syntax; clang's GCC-style
# -march=armv8-a+crc isn't accepted by zig's driver). "generic" keeps every
# aarch64 CPU supported, unlike pinning to e.g. apple_m1.
ARCH_FLAG=""
case "$CLASSIFIER" in
    *-aarch64) ARCH_FLAG="-mcpu=generic+crc" ;;
esac

CFLAGS="-O3 $ARCH_FLAG -DNDEBUG -DZSTD_MULTITHREAD=1 -DXXH_NAMESPACE=ZSTD_ $VIS_FLAG \
        $DLL_EXPORT_FLAG -I$ZSTD_LIB -I$ZSTD_LIB/common -fPIC"

# xargs -P keeps all $JOBS slots saturated (a fixed-size batch-then-wait loop
# idles every other core behind the batch's slowest TU) and, unlike bare `&` +
# `wait`, actually propagates a failing compile's exit status - xargs -P exits
# non-zero if any invocation does, which set -e then catches. A silently
# swallowed failed zig cc would otherwise surface only as a missing .o and a
# cryptic link error, or worse, a link that "succeeds" with a stale .o left
# over from a previous run.
compile_one() {
    zig cc -target "$ZIG_TARGET" $CFLAGS -c "$1" -o "$WORK/$(basename "$1").o"
}
export -f compile_one
export ZIG_TARGET CFLAGS WORK

printf '%s\n' $SRCS | xargs -P "$JOBS" -I{} bash -c 'compile_one "$@"' _ {}

# Link the shared library. zstd.h marks the public API with ZSTDLIB_VISIBLE,
# so -fvisibility=hidden keeps everything else internal.
SONAME_FLAG=""
[ "$LIB_NAME" = "libzstd.so" ] && SONAME_FLAG="-Wl,-soname,libzstd.so.1"

zig cc -target "$ZIG_TARGET" -shared $STRIP_FLAG $SONAME_FLAG $LINK_EXTRA -o "$DEST_DIR/$LIB_NAME" "$WORK"/*.o

# lld emits a .pdb (debug database, multiple MB) and a .lib (import library) next
# to a Windows .dll; neither is needed at runtime and both would be bundled into
# the native JAR. Keep only the shared library itself in the resources directory.
find "$DEST_DIR" -type f ! -name "$LIB_NAME" -delete

echo "[build-zstd] Installed: $DEST_DIR/$LIB_NAME"
