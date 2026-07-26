package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createBatteryTool(context: Context): Tool = Tool(
    name = "get_battery_info",
    description = "Get the current battery status of the device, including battery level, charging status, charge type, temperature, and health.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Unable to get battery info") }.toString()))
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val plug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargeType = when (plug) { BatteryManager.BATTERY_PLUGGED_AC -> "AC"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"; else -> if (charging) "Unknown" else "Not charging" }
            val temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val health = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) { BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"; BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"; BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; else -> "Unknown" }
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("level", pct); put("is_charging", charging); put("charge_type", chargeType); put("temperature_celsius", if (temp >= 0) temp / 10.0 else -1.0); put("health", health); put("message", "Battery: $pct%, Temp: ${if (temp >= 0) temp / 10.0 else "?"}°C, $health") }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "Unknown") }.toString())) }
    }
)
