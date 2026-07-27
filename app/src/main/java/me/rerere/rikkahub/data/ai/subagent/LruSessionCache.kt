package me.rerere.rikkahub.data.ai.subagent

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 线程安全的 LRU 会话缓存。
 *
 * 使用 [ReentrantReadWriteLock] 保证 get/put/evict 的原子性，
 * 内部用 [LinkedHashMap] (accessOrder=true) 实现 LRU 淘汰。
 *
 * 替代原来 ConcurrentHashMap + Collections.synchronizedList 的非原子组合，
 * 消除并行 spawn 完成时 store 操作的竞态条件。
 *
 * @param maxSize 最大缓存条目数，超出时自动淘汰最久未访问的条目
 */
class LruSessionCache<K, V>(private val maxSize: Int = 20) {

    private val lock = ReentrantReadWriteLock()

    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    /** 读取缓存，不存在返回 null。读锁。 */
    fun get(key: K): V? = lock.read { map[key] }

    /** 写入缓存，自动触发 LRU 淘汰。写锁。 */
    fun put(key: K, value: V): Unit = lock.write { map[key] = value }

    /** 移除指定条目，返回被移除的值。写锁。 */
    fun remove(key: K): V? = lock.write { map.remove(key) }

    /** 检查 key 是否存在。读锁。 */
    fun contains(key: K): Boolean = lock.read { map.containsKey(key) }

    /** 当前缓存大小。读锁。 */
    fun size(): Int = lock.read { map.size }

    /** 返回所有 key 的快照列表（按访问顺序，最久未访问在前）。读锁。 */
    fun keys(): List<K> = lock.read { map.keys.toList() }

    /**
     * 获取或计算：key 存在则返回，否则用 [default] 计算并缓存。
     * 先尝试读锁快速路径，miss 时升级为写锁。
     */
    fun getOrPut(key: K, default: () -> V): V {
        lock.read { map[key]?.let { return it } }
        return lock.write { map.getOrPut(key) { default() } }
    }

    /**
     * 原子更新：对已有条目应用变换函数。
     * 如果 key 不存在则不操作。
     */
    fun update(key: K, transform: (V) -> V): Boolean = lock.write {
        val existing = map[key] ?: return false
        map[key] = transform(existing)
        true
    }

    /** 返回所有条目的不可变快照，用于调试或 UI 展示。读锁。 */
    fun snapshot(): Map<K, V> = lock.read { LinkedHashMap(map) }

    /** 清空缓存。写锁。 */
    fun clear(): Unit = lock.write { map.clear() }

    override fun toString(): String = lock.read { "LruSessionCache(size=${map.size}, max=$maxSize)" }
}
