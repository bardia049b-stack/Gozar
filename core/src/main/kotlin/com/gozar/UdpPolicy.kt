package com.gozar

object UdpPolicy {
    const val NORMAL = "normal"
    const val GAME = "game"
    const val RELAY_ONLY = "relay_only"

    const val ROUTE_WARP = "warp"
    const val ROUTE_RELAY = "relay"
    const val ROUTE_DROP = "drop"

    val RELAY_PORTS: Set<Int> = setOf(80, 443, 3478, 8080, 8443)

    val GAME_PORTS: Set<Int> = buildSet {
        addAll(
            listOf(
                88, 500, 3074, 3478, 3479, 3544, 4500, 53, 3658, 3659, 5223,
                9307, 9306, 9295, 9296, 9297, 27015, 27031, 27036, 4380, 7777,
                8766, 27000, 27050, 51820, 1900, 5353
            )
        )
        addAll(6112..6119)
        addAll(19302..19309)
    }

    fun normalize(mode: String?): String = when (mode?.trim()?.lowercase()) {
        GAME -> GAME
        RELAY_ONLY -> RELAY_ONLY
        else -> NORMAL
    }

    fun isGamePort(port: Int): Boolean = port in GAME_PORTS

    fun effective(mode: String?, batterySaver: Boolean): String = when (normalize(mode)) {
        GAME -> if (batterySaver) NORMAL else GAME
        RELAY_ONLY -> RELAY_ONLY
        else -> NORMAL
    }

    fun route(
        mode: String?,
        consoleMode: Boolean,
        batterySaver: Boolean,
        port: Int,
        dns: Boolean
    ): String {
        if (!consoleMode) return ROUTE_DROP
        return when (effective(mode, batterySaver)) {
            GAME -> ROUTE_WARP
            RELAY_ONLY -> if (dns || port in RELAY_PORTS) ROUTE_RELAY else ROUTE_DROP
            else -> when {
                dns -> ROUTE_RELAY
                isGamePort(port) -> ROUTE_WARP
                port in RELAY_PORTS -> ROUTE_RELAY
                else -> ROUTE_DROP
            }
        }
    }
}

object UdpWarning {
    fun relayNeeded(consoleMode: Boolean, udpRelay: Boolean): Boolean = consoleMode && !udpRelay
}
