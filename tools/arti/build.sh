#!/usr/bin/env bash
#
# Builds the Arti JNI wrapper (src/lib.rs) and copies the libraries to where
# the app loads them from. The outputs are committed; CI does not build Arti.
#
#   android -> app/src/main/jniLibs/<abi>/libcitrine_arti.so   (cargo-ndk)
#
# Usage: ./build.sh            (Android is the only target)
#
# Prerequisites:
#   - rustup (rust-toolchain.toml pins the compiler; targets are added here)
#   - cargo-ndk, Android NDK $NDK_VERSION

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$SCRIPT_DIR"

NDK_VERSION="28.2.13676358"
MIN_SDK=26

ANDROID_OUT="$ROOT/app/src/main/jniLibs"

# Rust target -> jniLibs ABI. Must match splits.abi in app/build.gradle.kts.
ANDROID_TARGETS=(
    "aarch64-linux-android:arm64-v8a"
    "x86_64-linux-android:x86_64"
    "armv7-linux-androideabi:armeabi-v7a"
    "i686-linux-android:x86"
)

JNI_SYMBOLS=(getVersion setLogCallback initialize startSocksProxy stopSocksProxy startOnionService getOnionHostname stopOnionService isBootstrapped bootstrapProgressPermille destroy)

# Keep host paths out of the binaries.
export CARGO_INCREMENTAL=0
export RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo --remap-path-prefix=$SCRIPT_DIR=/arti"

add_target() {
    rustup target list --installed | grep -qx "$1" || rustup target add "$1"
}

check_symbols() {
    local lib="$1" exports
    case "$lib" in
        *.dylib) exports="$(nm -gU "$lib")" ;;   # Mach-O keeps exports in a compressed trie
        *.dll) exports="$(strings -a "$lib")" ;; # PE export table holds the plain names
        *) exports="$(nm -D "$lib")" ;;
    esac
    for sym in "${JNI_SYMBOLS[@]}"; do
        if [[ "$exports" != *"Java_com_greenart7c3_citrine_tor_ArtiNative_$sym"* ]]; then
            echo "Missing JNI symbol $sym in $lib" >&2
            exit 1
        fi
    done
}

find_ndk() {
    local candidate
    for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_HOME:-}/ndk/$NDK_VERSION" "${ANDROID_SDK_ROOT:-}/ndk/$NDK_VERSION" \
        "$HOME/Android/Sdk/ndk/$NDK_VERSION" "$HOME/Library/Android/sdk/ndk/$NDK_VERSION"; do
        [ -n "$candidate" ] && [ -f "$candidate/source.properties" ] || continue
        if grep -q "Pkg.Revision *= *$NDK_VERSION" "$candidate/source.properties"; then
            echo "$candidate"
            return
        fi
    done
    echo "Android NDK $NDK_VERSION not found (sdkmanager \"ndk;$NDK_VERSION\")" >&2
    exit 1
}

build_android() {
    command -v cargo-ndk > /dev/null || { echo "cargo-ndk not found (cargo install cargo-ndk)" >&2; exit 1; }
    ANDROID_NDK_HOME="$(find_ndk)"
    export ANDROID_NDK_HOME
    for entry in "${ANDROID_TARGETS[@]}"; do
        local target="${entry%%:*}" abi="${entry##*:}"
        echo "==> Android $abi ($target)"
        add_target "$target"
        cargo ndk -t "$target" --platform "$MIN_SDK" -o "$ANDROID_OUT" build --release --locked
        check_symbols "$ANDROID_OUT/$abi/libcitrine_arti.so"
    done
}

build_android

echo
( cd "$ROOT" && find app/src/main/jniLibs -type f -name '*.so' -exec shasum -a 256 {} + 2>/dev/null | sort -k2 ) || true
