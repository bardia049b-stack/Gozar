//go:build windows && 386

package wintun

import _ "embed"

//go:embed bin/wintun_386.dll
var library []byte
