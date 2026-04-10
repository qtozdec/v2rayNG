#!/bin/bash
set -e

# Build combined libv2ray.aar (AndroidLibXrayLite + olcRTC) via gomobile
# Prerequisites:
#   go install golang.org/x/mobile/cmd/gomobile@latest
#   gomobile init
#   Android SDK/NDK must be installed
#   AndroidLibXrayLite submodule must be checked out:
#     git submodule update --init AndroidLibXrayLite

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$SCRIPT_DIR/V2rayNG/app/libs/libv2ray.aar"

# Default targets
TARGETS="${TARGETS:-android/arm64,android/arm}"
MIN_API="${MIN_API:-24}"

echo "Building combined libv2ray.aar (xray-core + olcRTC)..."
echo "  Output:  $OUTPUT"
echo "  Targets: $TARGETS"
echo "  MinAPI:  $MIN_API"

cd "$SCRIPT_DIR"

# Ensure submodule is checked out
if [ ! -f AndroidLibXrayLite/go.mod ]; then
  echo "Checking out AndroidLibXrayLite submodule..."
  git submodule update --init AndroidLibXrayLite
fi

# Regenerate go.work (not tracked — local-only workspace file).
# Combining both modules into one workspace is what lets gomobile bind
# produce a single libgojni.so with both xray-core and olcRTC symbols.
rm -f go.work go.work.sum
go work init ./AndroidLibXrayLite ./olcrtc

# Download dependencies
go work sync

# Build combined .aar
gomobile bind \
  -target="$TARGETS" \
  -androidapi "$MIN_API" \
  -ldflags="-s -w -checklinkname=0" \
  -o "$OUTPUT" \
  github.com/2dust/AndroidLibXrayLite \
  github.com/openlibrecommunity/olcrtc/mobile

echo "Done: $OUTPUT"
ls -lh "$OUTPUT"
