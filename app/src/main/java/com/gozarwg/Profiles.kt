package com.gozarwg

import android.content.Context
import org.amnezia.awg.config.Config
import java.io.File

data class Profile(val id: String, val name: String, val text: String) {
    val awg: Boolean
        get() = Regex("(?m)^\\s*(Jc|Jmin|Jmax|S1|S2|S3|S4|H1|H2|H3|H4|I1|I2|I3|I4|I5)\\s*=\\s*\\S+").containsMatchIn(text)

    val endpoint: String
        get() = Regex("(?m)^\\s*Endpoint\\s*=\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: ""
}

object Profiles {
    private fun dir(c: Context): File {
        val d = File(c.filesDir, "profiles")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun names(c: Context) = c.getSharedPreferences("names", Context.MODE_PRIVATE)

    fun check(text: String): String? {
        return try {
            Config.parse(text.trim().byteInputStream(Charsets.UTF_8))
            null
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }

    fun all(c: Context): List<Profile> {
        val files = dir(c).listFiles { f -> f.name.endsWith(".conf") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            val id = f.nameWithoutExtension
            Profile(id, names(c).getString(id, id) ?: id, f.readText(Charsets.UTF_8))
        }
    }

    fun get(c: Context, id: String): Profile? {
        val f = File(dir(c), "$id.conf")
        if (!f.exists()) return null
        return Profile(id, names(c).getString(id, id) ?: id, f.readText(Charsets.UTF_8))
    }

    fun add(c: Context, text: String, name: String?): Profile? {
        val clean = text.trim()
        if (check(clean) != null) return null
        val id = "p" + System.currentTimeMillis().toString(36) + (100..999).random()
        File(dir(c), "$id.conf").writeText(clean, Charsets.UTF_8)
        val n = if (name.isNullOrBlank()) suggestName(clean) else name.trim()
        names(c).edit().putString(id, n).apply()
        return Profile(id, n, clean)
    }

    fun save(c: Context, id: String, name: String, text: String): Boolean {
        val clean = text.trim()
        if (check(clean) != null) return false
        File(dir(c), "$id.conf").writeText(clean, Charsets.UTF_8)
        names(c).edit().putString(id, name.trim()).apply()
        return true
    }

    /** Deletes both the config and every persisted selection reference, returning a real outcome. */
    fun delete(c: Context, id: String): Boolean {
        val file = File(dir(c), "$id.conf")
        if (file.exists() && !file.delete()) return false
        names(c).edit().remove(id).commit()
        if (Store.selected(c) == id) Store.setSelected(c, "")
        if (Store.lastProfile(c) == id) Store.setLastProfile(c, "")
        return !file.exists()
    }

    private fun suggestName(text: String): String {
        val host = Regex("(?m)^\\s*Endpoint\\s*=\\s*([^:\\s]+)").find(text)?.groupValues?.get(1)
        return host ?: "Profile"
    }
}
