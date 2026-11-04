#!/bin/sh
# OpenWrt package smoke test. This proves installation and init.d process
# startup in the official rootfs container. It is NOT a live-tunnel test.
set -eu

mkdir -p /var/lock /var/run/ubus /tmp
/usr/bin/gozar-core --version
sh -n /etc/init.d/gozar
test -f /etc/config/gozar

# The container is not booted by procd by default. Start the minimal service
# bus so /etc/init.d/gozar can exercise the real procd path.
if [ -x /sbin/ubusd ]; then
	/sbin/ubusd -s /var/run/ubus/ubus.sock &
	sleep 1
fi
if [ -x /sbin/procd ]; then
	/sbin/procd &
	sleep 1
fi

uci set gozar.main.enabled='1'
# Use the control socket for smoke: it stays alive without Mozilla credentials.
uci set gozar.main.command='--serve-control --control-socket /tmp/gozar-smoke.sock'
uci commit gozar
/etc/init.d/gozar start
sleep 2

pid="$(pidof gozar-core || true)"
if [ -z "$pid" ]; then
	echo 'FAIL: /etc/init.d/gozar did not start gozar-core' >&2
	logread 2>/dev/null | tail -n 40 >&2 || true
	exit 1
fi
echo "PASS: init.d started gozar-core pid=$pid"
/etc/init.d/gozar stop || true
sleep 1

/usr/bin/gozar-core --version
ping -c 1 -W 3 8.8.8.8 || echo 'ICMP egress unavailable in container smoke'
