#!/bin/sh
set -eu

version=$1
out=$2
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$out"
out=$(CDPATH='' cd -- "$out" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM

build_core() {
	goos=$1
	goarch=$2
	gomips=$3
	goarm=$4
	target=$5
	(cd "$root/go-desktop" && CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" GOMIPS="$gomips" GOARM="$goarm" \
		go build -trimpath -buildvcs=false -ldflags='-s -w' -o "$target" ./cmd/gozar)
}

installed_size() {
	du -sk "$1" | cut -f1
}

pack_core() {
	chip=$1
	binary=$2
	output=$3
	stage="$work/core-$chip"
	rm -rf "$stage"
	mkdir -p "$stage/control" "$stage/data/usr/bin" "$stage/data/etc/init.d" "$stage/data/etc/config" "$stage/data/usr/share/gozar"
	install -m 755 "$binary" "$stage/data/usr/bin/gozar-core"
	install -m 755 "$root/packaging/openwrt/files/gozar.init" "$stage/data/etc/init.d/gozar"
	install -m 644 "$root/packaging/openwrt/files/gozar.config" "$stage/data/etc/config/gozar"
	install -m 644 "$root/packaging/openwrt/files/README" "$stage/data/usr/share/gozar/README"
	printf '%s\n' '/etc/config/gozar' > "$stage/control/conffiles"
	cat > "$stage/control/control" <<CONTROL
Package: gozar
Version: $version-1
Depends: libc, kmod-tun
Source: package/gozar
Section: net
Priority: optional
Maintainer: Gozar contributors <noreply@github.com>
Architecture: $chip
Installed-Size: $(installed_size "$stage/data")
Description: Firefox Private Network client for OpenWrt
 Gozar ships the headless Go core, a procd service and its settings.
 The tunnel needs the kmod-tun package.
CONTROL
	( cd "$stage/data" && tar --format=gnu --owner=0 --group=0 -czf "$work/data.tar.gz" . )
	( cd "$stage/control" && tar --format=gnu --owner=0 --group=0 -czf "$work/control.tar.gz" . )
	printf '2.0\n' > "$work/debian-binary"
	rm -f "$output"
	# OpenWrt opkg expects a gzip tar outer (debian-binary, data, control), not Debian ar.
	( cd "$work" && tar --format=gnu --owner=0 --group=0 -cf - ./debian-binary ./data.tar.gz ./control.tar.gz | gzip -9n > "$output" )
}

pack_luci() {
	output=$1
	stage="$work/luci"
	rm -rf "$stage"
	mkdir -p "$stage/control" \
		"$stage/data/usr/lib/lua/luci/controller" \
		"$stage/data/usr/lib/lua/luci/model/cbi" \
		"$stage/data/usr/lib/lua/luci/view/gozar" \
		"$stage/data/usr/share/rpcd/acl.d"
	install -m 644 "$root/packaging/openwrt/luci/controller/gozar.lua" "$stage/data/usr/lib/lua/luci/controller/gozar.lua"
	install -m 644 "$root/packaging/openwrt/luci/model/cbi/gozar.lua" "$stage/data/usr/lib/lua/luci/model/cbi/gozar.lua"
	install -m 644 "$root/packaging/openwrt/luci/view/gozar/status.htm" "$stage/data/usr/lib/lua/luci/view/gozar/status.htm"
	install -m 644 "$root/packaging/openwrt/luci/acl/luci-app-gozar.json" "$stage/data/usr/share/rpcd/acl.d/luci-app-gozar.json"
	cat > "$stage/control/control" <<CONTROL
Package: luci-app-gozar
Version: $version-1
Depends: libc, luci-base, gozar
Source: package/luci-app-gozar
Section: luci
Priority: optional
Maintainer: Gozar contributors <noreply@github.com>
Architecture: all
Installed-Size: $(installed_size "$stage/data")
Description: LuCI support for Gozar
 Configuration and status pages for the Gozar proxy client.
CONTROL
	( cd "$stage/data" && tar --format=gnu --owner=0 --group=0 -czf "$work/data.tar.gz" . )
	( cd "$stage/control" && tar --format=gnu --owner=0 --group=0 -czf "$work/control.tar.gz" . )
	printf '2.0\n' > "$work/debian-binary"
	rm -f "$output"
	# OpenWrt opkg expects a gzip tar outer (debian-binary, data, control), not Debian ar.
	( cd "$work" && tar --format=gnu --owner=0 --group=0 -cf - ./debian-binary ./data.tar.gz ./control.tar.gz | gzip -9n > "$output" )
}

build_core linux mips softfloat 7 "$work/gozar-mips"
build_core linux mipsle softfloat 7 "$work/gozar-mipsel"
build_core linux arm "" 7 "$work/gozar-armv7"
build_core linux arm64 "" "" "$work/gozar-aarch64"

pack_core mips_24kc "$work/gozar-mips" "$out/Gozar-OpenWrt-mips_24kc.ipk"
pack_core mipsel_24kc "$work/gozar-mipsel" "$out/Gozar-OpenWrt-mipsel_24kc.ipk"
pack_core arm_cortex-a7_neon-vfpv4 "$work/gozar-armv7" "$out/Gozar-OpenWrt-armv7.ipk"
pack_core aarch64_cortex-a53 "$work/gozar-aarch64" "$out/Gozar-OpenWrt-aarch64.ipk"
pack_luci "$out/LuCI-Gozar-all.ipk"

if [ "${GOZAR_OPENWRT_X86_64:-0}" = 1 ]; then
	build_core linux amd64 "" "" "$work/gozar-x86_64"
	pack_core x86_64 "$work/gozar-x86_64" "$out/Gozar-OpenWrt-x86_64.ipk"
fi

ls -l "$out"/Gozar-OpenWrt-*.ipk "$out"/LuCI-Gozar-all.ipk
