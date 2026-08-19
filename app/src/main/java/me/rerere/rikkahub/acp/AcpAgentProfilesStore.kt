package me.rerere.rikkahub.acp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class ProfilesFile(
    val profiles: List<AcpAgentProfile> = emptyList(),
)

/**
 * ACP agent 配置的极简存储（JSON 文件 + 内存 StateFlow）。
 *
 * 提供一条内置的 codex 默认 profile 作为起点；后续可在设置页里增删改。
 * 因为 ACP profile 的 command 需要用户自己填（agent 二进制路径 / 参数），
 * 这里先走文件持久化，UI 管理留到下一阶段。
 */
class AcpAgentProfilesStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "acp_agent_profiles.json")
    private val _profiles = MutableStateFlow<List<AcpAgentProfile>>(emptyList())

    val profiles: StateFlow<List<AcpAgentProfile>> = _profiles.asStateFlow()

    init {
        _profiles.value = load()
    }

    fun get(id: String): AcpAgentProfile? = _profiles.value.firstOrNull { it.id == id }

    suspend fun upsert(profile: AcpAgentProfile) {
        val current = _profiles.value
        val next = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        _profiles.value = next
        persist(next)
    }

    suspend fun remove(id: String) {
        val next = _profiles.value.filterNot { it.id == id }
        _profiles.value = next
        persist(next)
    }

    private fun load(): List<AcpAgentProfile> = runCatching {
        if (!file.isFile) return DEFAULT_PROFILES
        json.decodeFromString<ProfilesFile>(file.readText()).profiles
            .ifEmpty { DEFAULT_PROFILES }
    }.getOrDefault(DEFAULT_PROFILES)

    private suspend fun persist(profiles: List<AcpAgentProfile>) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(ProfilesFile(profiles)))
        }
    }

    companion object {
        /** 内置默认：Codex + DeepSeek Harness（工作区 /workspace/acp 已配好 cordis.yml + omnibot 插件）。 */
        val DEFAULT_PROFILES = listOf(
            AcpAgentProfile(
                id = "codex",
                name = "Codex",
                command = "codex",
                arguments = emptyList(),
                cwd = "/workspace",
            ),
            AcpAgentProfile(
                id = "dsh",
                name = "DeepSeek Harness",
                command = "dsh-acp-demo",
                arguments = listOf("--config", "cordis.yml"),
                cwd = "/workspace/acp",
            ),
        )
    }
}
