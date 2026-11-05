package com.gozar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object GoSettings {

    fun advancedJson(c: Context): JSONObject {
        val options = JSONObject()
        for (name in Store.ADVANCED) {
            if (Store.advanced(c, name)) options.put(name, true)
        }
        if (Store.advanced(c, "doq")) options.put("doh_fallback", true)
        return options
    }

    fun paramsJson(c: Context): JSONObject {
        val params = JSONObject()
        val doq = Store.advancedParam(c, "doq_server")
        val ech = Store.advancedParam(c, "ech_config_list")
        val front = Store.advancedParam(c, "front_host")
        val path = Store.advancedParam(c, "wss_path")
        if (doq.isNotEmpty()) params.put("doq_server", doq)
        if (ech.isNotEmpty()) params.put("ech_config_list", ech)
        if (front.isNotEmpty()) params.put("front_host", front)
        if (path.isNotEmpty()) params.put("wss_path", path)
        return params
    }

    fun transportJson(c: Context): JSONObject = JSONObject()
        .put("advanced", advancedJson(c))
        .put("params", paramsJson(c))

    fun build(c: Context, location: FoxLocation, pass: String): String {
        val root = JSONObject()
            .put("proxy_host", location.host)
            .put("proxy_port", if (location.port > 0) location.port else 443)
            .put("pass", pass)
            .put("mtu", 1280)
            .put("udp", Store.udpRelay(c) || Store.gameMode(c) || Store.warp(c))
            .put("game_mode", Store.gameMode(c))
            .put("warp", Store.warp(c) || Store.gameMode(c))
            .put("advanced", advancedJson(c))
            .put("params", paramsJson(c))
        val endpoint = Store.warpEndpoint(c)
        if (endpoint.isNotEmpty()) root.put("warp_endpoint", endpoint)
        val identity = Store.warpIdentity(c)
        if (identity.isNotEmpty()) {
            try {
                root.put("warp_identity", JSONObject(identity))
            } catch (_: Exception) {
            }
        }
        val chain = JSONArray()
        for (hop in FoxRoute.split(Store.chain(c))) chain.put(hop)
        if (chain.length() > 0) root.put("chain", chain)
        val backups = JSONArray()
        for (place in Fox.servers) {
            if (backups.length() >= 2) break
            if (place.host.equals(location.host, ignoreCase = true) || place.host.isEmpty()) continue
            backups.put(place.host)
        }
        if (backups.length() > 0) root.put("backups", backups)
        return root.toString()
    }
}
