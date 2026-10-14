package com.beofoq.wg

import android.net.Uri
import android.util.Base64

object Link {
    private val schemes = setOf("gozarwg", "beofoqwg", "beofoq", "wireguard", "amneziawg", "awg")

    fun parse(raw: String): String? {
        val s = raw.trim()
        if (s.contains("[Interface]")) return s
        val uri = try {
            Uri.parse(s)
        } catch (e: Exception) {
            null
        }
        if (uri != null && schemes.contains(uri.scheme?.lowercase())) {
            val candidates = listOfNotNull(
                uri.getQueryParameter("config"),
                uri.getQueryParameter("data"),
                uri.getQueryParameter("text"),
                uri.getQueryParameter("url"),
                uri.fragment,
                uri.path?.trimStart('/')
            )
            for (cand in candidates) {
                val r = decodeCandidate(cand)
                if (r != null) return r
            }
            val whole = s.substringAfter("://").substringAfter("/")
            return decodeCandidate(whole)
        }
        return decodeCandidate(s)
    }

    fun nameOf(raw: String): String? {
        return try {
            Uri.parse(raw.trim()).getQueryParameter("name")
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeCandidate(cand: String): String? {
        val c = cand.trim()
        if (c.contains("[Interface]")) return c
        val decoded = tryDecodeBase64(c) ?: return null
        return if (decoded.contains("[Interface]")) decoded.trim() else null
    }

    private fun tryDecodeBase64(s: String): String? {
        val cleaned = s.replace("\\s".toRegex(), "")
        if (cleaned.length < 20) return null
        val flags = listOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        for (f in flags) {
            try {
                val out = String(Base64.decode(cleaned, f), Charsets.UTF_8)
                if (out.contains("[Interface]")) return out
            } catch (e: Exception) {
            }
        }
        return null
    }

    fun make(p: Profile): String {
        return "gozarwg://import?name=" + Uri.encode(p.name) + "&config=" + Uri.encode(p.text)
    }
}
