#!/usr/bin/env bash
# setup_nostrdb.sh — clone nostrdb C sources into app/src/main/cpp/nostrdb/
#
# Usage:
#   ./scripts/setup_nostrdb.sh [--update]
#
# After running this script, build the app with the nostrdb backend enabled:
#   ./gradlew assembleDebug -Pnostrdb
# or:
#   NOSTRDB=1 ./gradlew assembleDebug
#
# The NDK / CMake toolchain is configured in app/build.gradle.kts and
# app/src/main/cpp/CMakeLists.txt.
#
# Requirements:
#   - git
#   - Android NDK (set ANDROID_NDK_HOME or install via Android Studio)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CPP_DIR="$REPO_ROOT/app/src/main/cpp"
DEST="$CPP_DIR/nostrdb"

NOSTRDB_REPO="https://github.com/damus-io/nostrdb.git"
# Pin to a known-good commit; update here when upgrading nostrdb.
NOSTRDB_REF="main"

UPDATE=false
if [[ "${1:-}" == "--update" ]]; then
    UPDATE=true
fi

echo "==> nostrdb source directory: $DEST"

if [[ -d "$DEST/.git" ]]; then
    if $UPDATE; then
        echo "==> Updating existing clone..."
        git -C "$DEST" fetch origin
        git -C "$DEST" checkout "$NOSTRDB_REF"
        git -C "$DEST" pull --ff-only origin "$NOSTRDB_REF"
    else
        echo "==> nostrdb already cloned. Pass --update to refresh."
    fi
else
    echo "==> Cloning nostrdb from $NOSTRDB_REPO ..."
    mkdir -p "$CPP_DIR"
    git clone --depth 1 --branch "$NOSTRDB_REF" "$NOSTRDB_REPO" "$DEST"
fi

# Ensure git submodules (secp256k1, lmdb, flatcc) are present
echo "==> Initialising submodules..."
git -C "$DEST" submodule update --init --recursive

echo ""
echo "==> Done. Build with nostrdb:"
echo "    ./gradlew assembleDebug -Pnostrdb"
