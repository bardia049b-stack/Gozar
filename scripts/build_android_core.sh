#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="v0.0.0-20250506005352-78cd7a343bde"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an installed NDK}"
cd "$ROOT/go-desktop"
go install "golang.org/x/mobile/cmd/gomobile@$VERSION"
go install "golang.org/x/mobile/cmd/gobind@$VERSION"
go get "golang.org/x/mobile/bind@$VERSION"
mkdir -p "$ROOT/app/libs"
"$(go env GOPATH)/bin/gomobile" bind \
  -target=android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 24 -o "$ROOT/app/libs/gozarcore.aar" ./gozarcore
echo "built $ROOT/app/libs/gozarcore.aar"
