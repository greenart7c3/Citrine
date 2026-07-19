#!/usr/bin/env bash
# Builds the citrine-fips JNI library for Android into app/src/main/jniLibs.
#
# Prerequisites:
#   rustup target add aarch64-linux-android x86_64-linux-android
#   cargo install cargo-ndk
#   an Android NDK (ANDROID_NDK_HOME, or auto-detected from ANDROID_HOME/ndk)
#
# armeabi-v7a is currently skipped: fips-core 0.4.15 does not compile for
# 32-bit Android (libc msghdr field type mismatch). 32-bit devices fall back
# to the external-interface FIPS mode automatically.
#
# Usage: ./build-android.sh [--check] [output_dir]
#   --check     run `cargo check` for arm64 only (no .so output)
#   output_dir  jniLibs directory (default: ../../app/src/main/jniLibs)

set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    latest_ndk=$(ls -1 "$ANDROID_HOME/ndk" 2>/dev/null | sort -V | tail -1 || true)
    if [ -n "$latest_ndk" ]; then
        export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$latest_ndk"
    fi
fi

if [ "${1:-}" = "--check" ]; then
    exec cargo ndk -t arm64-v8a check
fi

out_dir="${1:-../../app/src/main/jniLibs}"
cargo ndk -t arm64-v8a -t x86_64 -o "$out_dir" build --release
