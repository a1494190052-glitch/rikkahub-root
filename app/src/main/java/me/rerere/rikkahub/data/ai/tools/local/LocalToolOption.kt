package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable @SerialName("javascript_engine") data object JavascriptEngine : LocalToolOption()
    @Serializable @SerialName("time_info") data object TimeInfo : LocalToolOption()
    @Serializable @SerialName("clipboard") data object Clipboard : LocalToolOption()
    @Serializable @SerialName("tts") data object Tts : LocalToolOption()
    @Serializable @SerialName("ask_user") data object AskUser : LocalToolOption()
    @Serializable @SerialName("ask_btw") data object AskBtw : LocalToolOption()
    @Serializable @SerialName("screen_time") data object ScreenTime : LocalToolOption()
    @Serializable @SerialName("calendar") data object Calendar : LocalToolOption()
    @Serializable @SerialName("root_shell") data object RootShell : LocalToolOption()
    @Serializable @SerialName("sub_agents") data object SubAgents : LocalToolOption()
    @Serializable @SerialName("scheduler") data object Scheduler : LocalToolOption()
    @Serializable @SerialName("battery") data object Battery : LocalToolOption()
    @Serializable @SerialName("brightness") data object Brightness : LocalToolOption()
    @Serializable @SerialName("torch") data object Torch : LocalToolOption()
    @Serializable @SerialName("vibrate") data object Vibrate : LocalToolOption()
    @Serializable @SerialName("volume") data object Volume : LocalToolOption()
    @Serializable @SerialName("wake_screen") data object WakeScreen : LocalToolOption()
    @Serializable @SerialName("wifi_info") data object WifiInfo : LocalToolOption()
    @Serializable @SerialName("telephony_info") data object TelephonyInfo : LocalToolOption()
    @Serializable @SerialName("storage_info") data object StorageInfo : LocalToolOption()
    @Serializable @SerialName("toast") data object Toast : LocalToolOption()
    @Serializable @SerialName("post_notification") data object PostNotification : LocalToolOption()
    @Serializable @SerialName("share") data object Share : LocalToolOption()
    @Serializable @SerialName("scan_media") data object ScanMedia : LocalToolOption()
}
