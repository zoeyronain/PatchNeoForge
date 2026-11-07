#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building Java components..."
cd "$DIR/java"
./gradlew jar --no-daemon

echo "==> Building Rust plugin..."
cd "$DIR/rust"
RUST_MIN_STACK=33554432 cargo build "$@"

TARGET_DIR="debug"
for arg in "$@"; do
    if [ "$arg" = "--release" ]; then
        TARGET_DIR="release"
    fi
done

# Check potential Pumpkin server plugin locations
SERVER_LOCATIONS=(
    "$DIR/../Pumpkin/plugins"
    "$DIR/../pumpkin-server/plugins"
    "$DIR/../../pumpkin-server/plugins"
)

for PLUGINS_DIR in "${SERVER_LOCATIONS[@]}"; do
    if [ -d "$PLUGINS_DIR" ]; then
        echo "==> Deploying to $PLUGINS_DIR..."
        cp --remove-destination -v "$DIR/rust/target/$TARGET_DIR/libpatchneoforge.so" "$PLUGINS_DIR/libpatchneoforge.so"
        DATA_JASSETS="$PLUGINS_DIR/data/patchneoforge/jassets"
        if [ -d "$DATA_JASSETS" ]; then
            cp --remove-destination -v "$DIR/java/patchneoforge/build/libs/patchneoforge.jar" "$DATA_JASSETS/patchneoforge.jar"
        fi
    fi
done

echo "==> Build complete!"
