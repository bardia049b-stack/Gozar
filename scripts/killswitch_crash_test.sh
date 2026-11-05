#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="gozarkill"
HOST_IF="gzk-host"
PEER_IF="gzk-peer"
TUN_IF="gzk-tun"
HOST_IP="10.99.0.1"
PEER_IP="10.99.0.2"

if [ "$(id -u)" -ne 0 ]; then
  echo "this test needs root" >&2
  exit 1
fi

cleanup() {
  ip netns del "$NS" 2>/dev/null || true
  ip link del "$HOST_IF" 2>/dev/null || true
  nft delete table inet gozar_kill 2>/dev/null || true
  rm -rf "${TMPDIR:-/tmp}/gozar-killswitch"
}
trap cleanup EXIT
cleanup

echo "== building the guard helper =="
(cd "$ROOT/go-desktop" && go build -o /tmp/gozar-guard ./cmd/gozar-guard)

echo "== isolated network namespace =="
ip netns add "$NS"
ip link add "$HOST_IF" type veth peer name "$PEER_IF"
ip link set "$PEER_IF" netns "$NS"
ip addr add "$HOST_IP/24" dev "$HOST_IF"
ip link set "$HOST_IF" up
ip netns exec "$NS" ip addr add "$PEER_IP/24" dev "$PEER_IF"
ip netns exec "$NS" ip link set "$PEER_IF" up
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip link add "$TUN_IF" type dummy
ip netns exec "$NS" ip link set "$TUN_IF" up

reach() {
  ip netns exec "$NS" ping -c 2 -W 2 "$HOST_IP" >/dev/null 2>&1
}

echo "== baseline: the peer can reach the host =="
if ! reach; then
  echo "FAIL: baseline connectivity is broken, the test cannot prove anything" >&2
  exit 1
fi
echo "PASS baseline reachable"

echo "== apply the kill switch inside the namespace =="
ip netns exec "$NS" env TMPDIR=/tmp /tmp/gozar-guard -action apply -iface "$TUN_IF"
ip netns exec "$NS" nft list table inet gozar_kill

echo "== traffic must now be cut =="
if reach; then
  echo "FAIL: traffic still flows with the kill switch applied" >&2
  exit 1
fi
echo "PASS traffic cut"

echo "== simulate a crash: SIGKILL a holder, rules must survive =="
ip netns exec "$NS" env TMPDIR=/tmp /tmp/gozar-guard -action hold -iface "$TUN_IF" &
HOLDER=$!
sleep 2
kill -9 "$HOLDER" 2>/dev/null || true
wait "$HOLDER" 2>/dev/null || true
if reach; then
  echo "FAIL: the network came back after the process was killed" >&2
  exit 1
fi
echo "PASS still cut after SIGKILL"

echo "== restart reaps the stale guard and restores the network =="
ip netns exec "$NS" env TMPDIR=/tmp /tmp/gozar-guard -action reap
if ! reach; then
  echo "FAIL: the network did not come back after reaping" >&2
  ip netns exec "$NS" nft list ruleset || true
  exit 1
fi
echo "PASS restored after reap"

echo "== apply and remove cleanly =="
ip netns exec "$NS" env TMPDIR=/tmp /tmp/gozar-guard -action apply -iface "$TUN_IF"
if reach; then
  echo "FAIL: second apply did not cut traffic" >&2
  exit 1
fi
ip netns exec "$NS" env TMPDIR=/tmp /tmp/gozar-guard -action remove
if ! reach; then
  echo "FAIL: clean remove did not restore traffic" >&2
  exit 1
fi
echo "PASS clean apply/remove cycle"

echo "ALL KILL SWITCH CHECKS PASSED"
