package me.rerere.rikkahub.data.model

import java.util.concurrent.ConcurrentHashMap

/**
 * 角色卡变量存储 (MVU / TavoJS 变量桥的原生侧)
 *
 * - MVU 数据按 messageId 存整份 JSON 快照 (对齐 MagVarUpdate 语义)
 * - TavoJS 变量按 messageId+name 存单个值
 *
 * 当前为内存级实现 (应用存活期间有效), 持久化留待后续版本。
 */
object CardVariableStore {
    /** messageId -> MVU 数据 JSON */
    private val mvuData = ConcurrentHashMap<String, String>()

    /** "messageId:name" -> 变量值 JSON */
    private val vars = ConcurrentHashMap<String, String>()

    fun getData(messageId: String): String = mvuData[messageId] ?: "{}"

    fun setData(messageId: String, json: String) {
        mvuData[messageId] = json
    }

    /** 仅当不存在时写入 (导入种入初始变量, 不覆盖运行时进度) */
    fun setDataIfAbsent(messageId: String, json: String) {
        mvuData.putIfAbsent(messageId, json)
    }

    fun removeData(messageId: String) {
        mvuData.remove(messageId)
    }

    fun getVar(messageId: String, name: String): String? = vars["$messageId:$name"]

    fun setVar(messageId: String, name: String, json: String) {
        vars["$messageId:$name"] = json
    }

    fun unsetVar(messageId: String, name: String) {
        vars.remove("$messageId:$name")
    }
}
