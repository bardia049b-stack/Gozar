//go:build windows && amd64

package wintun

import _ "embed"

//go:embed bin/wintun_amd64.dll
var library []byte
