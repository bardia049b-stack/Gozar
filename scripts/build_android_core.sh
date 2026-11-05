#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-$(sed -n 's/.*Version = "\([^"]*\)".*/\1/p' "$ROOT/go-desktop/internal/version/version.go" | head -1)}"
MOBILE_VERSION="v0.0.0-20261011034528-197770444867"
OUT="$ROOT/core/libs/gozar-core.aar"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    [ -n "$base" ] || continue
    candidate="$(ls -d "$base"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$candidate" ]; then
      export ANDROID_NDK_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME:-}" ]; then
  echo "ANDROID_NDK_HOME is not set and no NDK was found in the SDK" >&2
  exit 1
fi

echo "NDK: $ANDROID_NDK_HOME"
echo "Version: $VERSION"

export CGO_ENABLED=1
export GOFLAGS="${GOFLAGS:-} -mod=mod"
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
export PATH="$(go env GOPATH)/bin:$PATH"

mkdir -p "$ROOT/core/libs"
cd "$ROOT/go-desktop"
go mod download golang.org/x/mobile
gomobile bind \
  -target=android/arm64,android/arm,android/386,android/amd64 \
  -androidapi 24 \
  -trimpath \
  -ldflags="-s -w -X github.com/bardia049b-stack/Gozar1/go-desktop/mobile.version=$VERSION" \
  -o "$OUT" \
  ./mobile

ls -l "$OUT"
unzip -l "$OUT" | grep -E 'libgojni.so|classes.jar'
echo "Android core built: $OUT"
