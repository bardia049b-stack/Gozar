package com.gozarwg

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class FoxJar {

    private val store = HashMap<String, String>()

    fun header(): String = store.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun absorb(reply: FoxReply) {
        for ((name, value) in reply.headers) {
            if (!name.equals("Set-Cookie", true)) continue
            for (piece in value.split(",\\s*(?=[A-Za-z0-9_\\-]+=)".toRegex())) {
                val pair = piece.split(";").firstOrNull()?.trim() ?: continue
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                store[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
        }
    }

    fun clear() = store.clear()
}

object FoxShield {

    private const val SITE = "https://accounts.firefox.com"
    const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val prefix = Pattern.compile("/_fs-ch-[A-Za-z0-9]+")
    private val init = Pattern.compile("init\\((\\[[^\\]]*\\]),\\s*\"([^\"]+)\",\\s*\"([^\"]+)\"")
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun solve(jar: FoxJar, origin: String = SITE): Boolean {
        if (origin != SITE && solveHost(jar, origin)) return true
        return solveHost(jar, SITE)
    }

    private fun solveHost(jar: FoxJar, host: String): Boolean {
        val page = fetch(jar, host + "/", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") ?: return false
        if (!page.contains("/_fs-ch-")) return true
        val matcher = prefix.matcher(page)
        if (!matcher.find()) return false
        val path = matcher.group()
        val script = fetch(jar, host + path + "/script.js?reload=true", "*/*") ?: return false
        val call = init.matcher(script)
        var last: Any? = null
        var found: java.util.regex.MatchResult? = null
        while (call.find()) {
            last = call.toMatchResult()
            found = last as java.util.regex.MatchResult
        }
        val match = found ?: return false
        var tasks = JSONArray(match.group(1))
        var token = match.group(2)

        for (round in 0 until 8) {
            val answers = JSONArray()
            for (i in 0 until tasks.length()) {
                val task = tasks.optJSONObject(i) ?: continue
                answers.put(answer(jar, host, host + path, token, task) ?: return false)
            }
            val body = JSONObject().put("token", token).put("data", answers).toString()
            val reply = send(jar, host + path + "/fst-post-back", "POST", body, mapOf("Origin" to host, "Accept" to "application/json")) ?: return false
            if (reply.code != 200) return false
            val back = try {
                JSONObject(reply.body)
            } catch (_: Exception) {
                return false
            }
            if (back.optString("status") == "success") return jar.header().isNotEmpty()
            val next = back.optJSONArray("ch") ?: return false
            val nextToken = back.optString("tok")
            if (next.length() == 0 || nextToken.isEmpty()) return false
            tasks = next
            token = nextToken
        }
        return false
    }

    private fun answer(jar: FoxJar, host: String, path: String, token: String, task: JSONObject): JSONObject? {
        return when (task.optString("ty")) {
            "pow" -> {
                val data = task.optJSONObject("data") ?: return null
                val base = data.optString("base")
                val target = data.optString("hash")
                val suffix = brute(base, target) ?: return null
                JSONObject()
                    .put("ty", "pow")
                    .put("base", base)
                    .put("answer", suffix)
                    .put("hmac", data.optString("hmac"))
                    .put("expires", data.optString("expires"))
            }
            "pat" -> {
                val reply = send(
                    jar,
                    path + "/pat?token=" + java.net.URLEncoder.encode(token, "UTF-8"),
                    "POST",
                    "",
                    mapOf("Accept" to "text/plain", "Content-Type" to "application/json", "Origin" to host)
                )
                var auth = ""
                if (reply != null && reply.code == 200 && reply.body.isNotEmpty()) {
                    auth = try {
                        JSONObject(reply.body).optString("auth")
                    } catch (_: Exception) {
                        ""
                    }
                }
                JSONObject().put("ty", "pat").put("auth", auth)
            }
            "clientmetrics" -> JSONObject()
                .put("ty", "clientmetrics")
                .put("webdriver", false)
                .put("bot_detection_result", JSONObject().put("bot_detected", false).put("bot_kind", JSONObject.NULL))
                .put("browser_metrics", JSONObject().put("client_data", "{}").put("error_trace", JSONObject.NULL))
                .put("detector_results", JSONObject())
                .put("v", 2)
            else -> null
        }
    }

    private fun brute(base: String, targetHex: String): String? {
        val target = try {
            FoxCrypto.unhex(targetHex)
        } catch (_: Exception) {
            return null
        }
        if (target.size != 32) return null
        for (a in ALPHABET) {
            for (b in ALPHABET) {
                val candidate = base + a + b
                if (FoxCrypto.sha256(candidate.toByteArray(Charsets.UTF_8)).contentEquals(target)) {
                    return "$a$b"
                }
            }
        }
        return null
    }

    private fun fetch(jar: FoxJar, url: String, accept: String): String? {
        val reply = send(jar, url, "GET", null, mapOf("Accept" to accept))
        return if (reply != null && reply.code == 200) reply.body else null
    }

    fun send(jar: FoxJar, url: String, method: String, body: String?, extra: Map<String, String>): FoxReply? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", AGENT)
        if (jar.header().isNotEmpty()) connection.setRequestProperty("Cookie", jar.header())
        for ((k, v) in extra) connection.setRequestProperty(k, v)
        if (body != null && body.isNotEmpty()) connection.setRequestProperty("Content-Type", "application/json")
        return try {
            if (body != null && body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val found = HashMap<String, String>()
            for ((k, values) in connection.headerFields) {
                if (k == null || values.isEmpty()) continue
                found[k] = values.joinToString(",")
            }
            val reply = FoxReply(code, text, found)
            jar.absorb(reply)
            reply
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
