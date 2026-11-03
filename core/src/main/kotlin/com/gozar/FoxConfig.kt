package com.gozar

import org.json.JSONObject
import java.io.File

data class FoxConfig(
    val udpMode: String = UdpPolicy.NORMAL,
    val consoleMode: Boolean = false,
    val batterySaver: Boolean = false,
    val warpVia: String = DIRECT
) {

    fun toJson(): String = JSONObject()
        .put("udpMode", UdpPolicy.normalize(udpMode))
        .put("consoleMode", consoleMode)
        .put("batterySaver", batterySaver)
        .put("warpVia", normalizeVia(warpVia))
        .put("version", VERSION)
        .toString()

    companion object {
        const val FILE = "gozar-config.json"
        const val VERSION = 3
        const val DIRECT = "direct"
        const val RELAY = "relay"

        fun normalizeVia(value: String?): String = if (value == RELAY) RELAY else DIRECT

        fun fromJson(text: String?): FoxConfig {
            if (text.isNullOrBlank()) return FoxConfig()
            return try {
                val json = JSONObject(text)
                FoxConfig(
                    udpMode = UdpPolicy.normalize(json.optString("udpMode", UdpPolicy.NORMAL)),
                    consoleMode = json.optBoolean("consoleMode", false),
                    batterySaver = json.optBoolean("batterySaver", false),
                    warpVia = normalizeVia(json.optString("warpVia", DIRECT))
                )
            } catch (_: Exception) {

                FoxConfig()
            }
        }

        fun load(dir: File): FoxConfig = try {
            val file = File(dir, FILE)
            if (file.isFile) fromJson(file.readText()) else FoxConfig()
        } catch (_: Exception) {
            FoxConfig()
        }

        fun save(dir: File, config: FoxConfig): Boolean = try {
            dir.mkdirs()
            val target = File(dir, FILE)
            val temporary = File(dir, "$FILE.tmp")
            temporary.writeText(config.toJson())
            temporary.renameTo(target)
        } catch (_: Exception) {
            false
        }
    }
}
