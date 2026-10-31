package com.gozar

internal class RootPlan(
    private val interfaceName: String,
    private val processId: Int,
    private val startedAt: String,
    private val flag: String,
    private val suffix: String,
    private val table: Int,
    private val priority: Int,
    private val bypassLan: Boolean
) {
    init {
        require(interfaceName.matches(Regex("[A-Za-z0-9_.-]{1,15}")))
        require(processId > 0 && startedAt.matches(Regex("[0-9]+")))
        require(flag.startsWith("/") && !flag.contains('\n') && !flag.contains('\r'))
        require(suffix.matches(Regex("[A-Fa-f0-9]{8}")))
        require(table in 30000..59999 && priority in 7000..9999)
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun commands(): String {
        val chain = "GZR$suffix"
        val lan = if (bypassLan) """
            iptables -t mangle -A "${'$'}CHAIN" -d 10.111.0.53/32 -p udp --dport 53 -j MARK --set-xmark "${'$'}MARK"
            iptables -t mangle -A "${'$'}CHAIN" -d 10.111.0.53/32 -p tcp --dport 53 -j MARK --set-xmark "${'$'}MARK"
            iptables -t mangle -A "${'$'}CHAIN" -d 10.0.0.0/8 -j RETURN
            iptables -t mangle -A "${'$'}CHAIN" -d 172.16.0.0/12 -j RETURN
            iptables -t mangle -A "${'$'}CHAIN" -d 192.168.0.0/16 -j RETURN
            iptables -t mangle -A "${'$'}CHAIN" -d 169.254.0.0/16 -j RETURN
        """.trimIndent() else ""
        return """
            set -e
            IFACE=${quote(interfaceName)}
            FLAG=${quote(flag)}
            CHAIN=${quote(chain)}
            TABLE=$table
            PREF=$priority
            APP_PID=$processId
            APP_START=${quote(startedAt)}
            MARK=0x40000000/0x40000000
            route=0
            rule=0
            chain=0
            nat=0
            output=0
            cleanup() {
                if [ "${'$'}output" -eq 1 ]; then iptables -t mangle -D OUTPUT -m owner --uid-owner 0 -j "${'$'}CHAIN" 2>/dev/null || :; fi
                if [ "${'$'}nat" -eq 1 ]; then iptables -t nat -D POSTROUTING -m owner --uid-owner 0 -m mark --mark "${'$'}MARK" -o "${'$'}IFACE" -j SNAT --to-source 10.111.0.2 2>/dev/null || :; fi
                if [ "${'$'}chain" -eq 1 ]; then iptables -t mangle -F "${'$'}CHAIN" 2>/dev/null || :; iptables -t mangle -X "${'$'}CHAIN" 2>/dev/null || :; fi
                if [ "${'$'}rule" -eq 1 ]; then ip -4 rule del pref "${'$'}PREF" fwmark "${'$'}MARK" table "${'$'}TABLE" 2>/dev/null || :; fi
                if [ "${'$'}route" -eq 1 ]; then ip -4 route del table "${'$'}TABLE" default dev "${'$'}IFACE" src 10.111.0.2 2>/dev/null || :; fi
            }
            trap cleanup EXIT
            trap 'exit 1' HUP INT TERM
            [ "${'$'}(id -u)" = 0 ]
            [ -f "${'$'}FLAG" ]
            command -v ip >/dev/null
            command -v iptables >/dev/null
            ip -o link show "${'$'}IFACE" >/dev/null
            if ip -4 rule show | grep -q "^${'$'}PREF:"; then exit 1; fi
            occupied=${'$'}(ip -4 route show table "${'$'}TABLE" 2>/dev/null || :)
            [ -z "${'$'}occupied" ]
            if iptables -t mangle -S "${'$'}CHAIN" >/dev/null 2>&1; then exit 1; fi
            ip -4 route add table "${'$'}TABLE" default dev "${'$'}IFACE" src 10.111.0.2
            route=1
            ip -4 rule add pref "${'$'}PREF" fwmark "${'$'}MARK" table "${'$'}TABLE"
            rule=1
            iptables -t mangle -N "${'$'}CHAIN"
            chain=1
            iptables -t mangle -A "${'$'}CHAIN" -m mark --mark 0x20000/0x20000 -j RETURN
            iptables -t mangle -A "${'$'}CHAIN" -m mark --mark 0x10000/0x10000 -j RETURN
            iptables -t mangle -A "${'$'}CHAIN" -d 127.0.0.0/8 -j RETURN
            iptables -t mangle -A "${'$'}CHAIN" -m addrtype --dst-type LOCAL -j RETURN
            $lan
            iptables -t mangle -A "${'$'}CHAIN" -p tcp -j MARK --set-xmark "${'$'}MARK"
            iptables -t mangle -A "${'$'}CHAIN" -p udp --dport 53 -j MARK --set-xmark "${'$'}MARK"
            iptables -t nat -I POSTROUTING 1 -m owner --uid-owner 0 -m mark --mark "${'$'}MARK" -o "${'$'}IFACE" -j SNAT --to-source 10.111.0.2
            nat=1
            iptables -t mangle -I OUTPUT 1 -m owner --uid-owner 0 -j "${'$'}CHAIN"
            output=1
            echo GOZAR_ROOT_READY
            while [ -f "${'$'}FLAG" ]; do
                current=${'$'}(sed 's/^[^)]*) //' "/proc/${'$'}APP_PID/stat" 2>/dev/null | cut -d ' ' -f 20 || :)
                [ "${'$'}current" = "${'$'}APP_START" ] || break
                sleep 1
            done
        """.trimIndent()
    }
}
