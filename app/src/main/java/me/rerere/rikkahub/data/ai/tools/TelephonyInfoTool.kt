package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun createTelephonyInfoTool(context: Context): Tool = Tool(
    name = "get_telephony_info", description = "Get SIM card info and carrier information. Requires READ_PHONE_STATE permission.", needsApproval = { true },
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "READ_PHONE_STATE not granted") }.toString()))
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)!!
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true); put("has_sim", tm.simState == TelephonyManager.SIM_STATE_READY)
                put("sim_operator_name", try { tm.simOperatorName ?: "" } catch (_: Exception) { "" })
                put("network_operator_name", try { tm.networkOperatorName ?: "" } catch (_: Exception) { "" })
                put("network_country", try { tm.networkCountryIso ?: "" } catch (_: Exception) { "" })
            }.toString()))
        } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "") }.toString())) }
    }
)
