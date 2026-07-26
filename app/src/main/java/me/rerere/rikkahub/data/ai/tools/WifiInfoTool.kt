package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createWifiInfoTool(context: Context): Tool = Tool(
    name = "get_wifi_info", description = "Get current WiFi connection info (SSID, BSSID, IP, signal strength, link speed).", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Location permission required") }.toString()))
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)!!; val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("connected", false) }.toString()))
            val wm = context.getSystemService(WifiManager::class.java)!!; val ci = wm.connectionInfo
            val ssid = ci.ssid ?: ""; val bssid = ci.bssid ?: ""; val redacted = ssid == "<unknown ssid>" || bssid == "02:00:00:00:00:00"
            val result = buildJsonObject {
                put("success", true); put("connected", true); put("ssid_redacted", redacted)
                if (!redacted) { put("ssid", ssid.removeSurrounding("\"")); put("bssid", bssid) }
                put("link_speed_mbps", ci.linkSpeed); put("rssi_dbm", ci.rssi); put("frequency_mhz", ci.frequency)
                cm.activeNetwork?.let { cm.getLinkProperties(it) }?.linkAddresses?.firstOrNull()?.address?.hostAddress?.let { put("ip_address", it) }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
