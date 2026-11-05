package com.gozar

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object GoCore {

    @Volatile private var loaded = false
    @Volatile private var checked = false
    private var api: Class<*>? = null
    private var protectorType: Class<*>? = null
    private val methods = HashMap<String, Method>()
    private val lock = Any()

    val available: Boolean
        get() {
            if (!checked) load()
            return loaded
        }

    private fun load() {
        synchronized(lock) {
            if (checked) return
            checked = true
            try {
                val clazz = Class.forName("mobile.Mobile")
                api = clazz
                protectorType = Class.forName("mobile.Protector")
                for (method in clazz.methods) methods[method.name.lowercase()] = method
                loaded = true
            } catch (_: Throwable) {
                loaded = false
                api = null
            }
        }
    }

    private fun method(name: String): Method? {
        if (!available) return null
        return methods[name.lowercase()]
    }

    private fun adapt(value: Any?, type: Class<*>): Any? {
        if (value == null) return null
        if (value is Number) {
            return when (type) {
                java.lang.Long.TYPE, java.lang.Long::class.java -> value.toLong()
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> value.toInt()
                java.lang.Short.TYPE -> value.toShort()
                java.lang.Byte.TYPE -> value.toByte()
                java.lang.Double.TYPE -> value.toDouble()
                java.lang.Float.TYPE -> value.toFloat()
                else -> value
            }
        }
        return value
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val target = method(name) ?: throw IllegalStateException("the Go core is not bundled in this build")
        val types = target.parameterTypes
        if (types.size != args.size) throw IllegalStateException("$name expects ${types.size} arguments")
        val prepared = arrayOfNulls<Any>(args.size)
        for (index in args.indices) prepared[index] = adapt(args[index], types[index])
        return try {
            target.invoke(null, *prepared)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    fun version(): String = try {
        call("Version") as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun bindProtector(protect: (Int) -> Boolean) {
        val type = protectorType ?: return
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "protect", "Protect" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                    protect(fd)
                }
                "toString" -> "GozarProtector"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.firstOrNull() === this
                else -> null
            }
        }
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
        try {
            call("SetProtector", proxy)
        } catch (_: Throwable) {
        }
    }

    fun start(fd: Int, settings: String) {
        call("Start", fd, settings)
    }

    fun stop() {
        try {
            call("Stop")
        } catch (_: Throwable) {
        }
    }

    fun running(): Boolean = try {
        call("Running") as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    fun stats(): String = try {
        call("Stats") as? String ?: "{}"
    } catch (_: Throwable) {
        "{}"
    }

    fun setAdvanced(settings: String) {
        call("SetAdvanced", settings)
    }

    fun registerWarp(): String = call("WarpRegister") as? String ?: ""

    fun scanWarp(endpoints: String): String = try {
        call("WarpScan", endpoints) as? String ?: "[]"
    } catch (_: Throwable) {
        "[]"
    }

    fun probe(host: String, port: Int): String = try {
        call("Probe", host, port) as? String ?: "{}"
    } catch (_: Throwable) {
        "{}"
    }

    fun validate(settings: String): String = try {
        call("Validate", settings) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }
}
