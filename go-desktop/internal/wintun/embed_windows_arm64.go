//go:build windows && arm64

package wintun

import _ "embed"

//go:embed bin/wintun_arm64.dll
var library []byte
