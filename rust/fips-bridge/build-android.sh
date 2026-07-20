#!/usr/bin/env bash
# Regenerates the committed citrine-fips JNI library for Android under
# app/src/main/jniLibs.
#
# The compiled .so is prebuilt and committed to the repo, so ordinary Gradle
# builds do NOT run this — it is a manual tool. Run it after changing anything
# in rust/fips-bridge, then commit the updated .so files.
#
# Prerequisites (the script fails with these exact commands if any are missing):
#   rustup target add aarch64-linux-android x86_64-linux-android
#   cargo install cargo-ndk
#   an Android NDK (ANDROID_NDK_HOME / ANDROID_NDK_ROOT, or auto-detected from
#   ANDROID_HOME/ndk)
#
# armeabi-v7a/x86 are skipped: fips-core 0.4.15 does not compile for 32-bit
# Android (libc msghdr field type mismatch). Those ABIs ship without the lib
# and fall back to external-interface FIPS mode automatically.
#
# Usage: ./build-android.sh [--check] [output_dir]
#   --check     run `cargo check` for arm64 only (no .so output)
#   output_dir  jniLibs directory (default: ../../app/src/main/jniLibs)

set -euo pipefail
cd "$(dirname "$0")"

TARGETS=(aarch64-linux-android x86_64-linux-android)

fail() {
    echo "error: $1" >&2
    exit 1
}

# --- Preflight: the FIPS library is required, so surface missing tooling clearly.
command -v cargo >/dev/null 2>&1 || fail \
    "cargo not found on PATH. Install Rust from https://rustup.rs and re-run."

command -v cargo-ndk >/dev/null 2>&1 || fail \
    "cargo-ndk not found on PATH. Install it with: cargo install cargo-ndk"

installed_targets="$(rustup target list --installed 2>/dev/null || true)"
missing_targets=()
for target in "${TARGETS[@]}"; do
    grep -qx "$target" <<<"$installed_targets" || missing_targets+=("$target")
done
if [ "${#missing_targets[@]}" -ne 0 ]; then
    fail "missing Rust targets. Install them with: rustup target add ${missing_targets[*]}"
fi

# --- Locate an NDK. cargo-ndk self-detects from these vars too; this fills in
# the ANDROID_HOME/ndk case and is a no-op when a var is already set.
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
        export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
    elif [ -n "${ANDROID_NDK_LATEST_HOME:-}" ]; then
        export ANDROID_NDK_HOME="$ANDROID_NDK_LATEST_HOME"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
        latest_ndk="$(ls -1 "$ANDROID_HOME/ndk" 2>/dev/null | sort -V | tail -1 || true)"
        [ -n "$latest_ndk" ] && export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$latest_ndk"
    fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    fail "no Android NDK found. Set ANDROID_NDK_HOME, or install one with: sdkmanager \"ndk;27.2.12479018\""
fi

if [ "${1:-}" = "--check" ]; then
    exec cargo ndk -t arm64-v8a check
fi

out_dir="${1:-../../app/src/main/jniLibs}"
cargo ndk -t arm64-v8a -t x86_64 -o "$out_dir" build --release
