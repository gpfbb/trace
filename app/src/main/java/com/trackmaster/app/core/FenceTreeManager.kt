package com.trackmaster.app.core

import android.content.Context
import android.content.SharedPreferences
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.utils.GeoMath
import com.trackmaster.app.utils.buildFenceTree
import com.trackmaster.app.utils.FenceTreeResult

/**
 * 围栏层级关系管理器
 *
 * 职责：
 * - 管理 fenceId → parentId 的映射（存储在 SharedPreferences，不改 DB schema）
 * - 加载围栏列表 → 应用 parent map → 填充 depth → 构建围栏树
 * - 提供设置/移除/查询父子关系的 API
 *
 * SharedPreferences 结构：
 * - key: "fence_parent_{fenceId}", value: parentId
 * - key: "fence_tree_version", value: Int（变更计数器，用于缓存失效）
 */
class FenceTreeManager(private val context: Context) {

    companion object {
        private const val TAG = "FenceTreeManager"
        private const val PREFS_NAME = "fence_tree_prefs"
        private const val KEY_PREFIX = "fence_parent_"
        private const val KEY_VERSION = "fence_tree_version"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前 parent map 的版本号（每次变更递增） */
    var version: Int = 0
        private set

    init {
        version = prefs.getInt(KEY_VERSION, 0)
    }

    // ============================================================
    // Parent Map 操作
    // ============================================================

    /** 设置围栏的父级（null = 设为顶级） */
    fun setParent(fenceId: String, parentId: String?) {
        // 防止循环引用：不能设自己为父级
        if (fenceId == parentId) {
            AppLog.w(TAG, "不能设置围栏自身为父级: $fenceId")
            return
        }
        // 【P0修复】将父子关系写入和版本号递增合并为单次原子commit
        // 避免apply()异步写入和bumpVersion()之间发生crash导致数据不一致
        val edit = prefs.edit()
        if (parentId == null) {
            edit.remove("$KEY_PREFIX$fenceId")
        } else {
            edit.putString("$KEY_PREFIX$fenceId", parentId)
        }
        version++
        edit.putInt(KEY_VERSION, version)
        edit.commit()
        AppLog.d(TAG, "设置父级: $fenceId → ${parentId ?: "顶级"}")
    }

    /** 获取围栏的父级 ID */
    fun getParent(fenceId: String): String? =
        prefs.getString("$KEY_PREFIX$fenceId", null)

    /** 移除围栏的父级关系（设为顶级） */
    fun removeParent(fenceId: String) {
        setParent(fenceId, null)
    }

    /** 获取完整的 parent map { fenceId: parentId } */
    fun getParentMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            // 只处理以 KEY_PREFIX 开头的键（排除 VERSION 键等）
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val fenceId = key.removePrefix(KEY_PREFIX)
                result[fenceId] = value
            }
        }
        return result
    }

    /** 清除所有父子关系 */
    fun clearAll() {
        // 【P0修复】合并为单次原子commit，避免clear和version bump之间的crash导致不一致
        version++
        prefs.edit().clear().putInt(KEY_VERSION, version).commit()
        AppLog.d(TAG, "已清除所有父子关系")
    }

    // ============================================================
    // 围栏树加载
    // ============================================================

    /**
     * 将旧围栏列表转换为核心 Geofence 并构建围栏树
     *
     * 流程：
     * 1. 旧 GeoFence → 核心 Geofence（FenceMapper）
     * 2. 应用 parent map
     * 3. 填充 depth
     *
     * @return 带 parentId 和 depth 的核心 Geofence 列表
     */
    fun loadFenceTree(oldFences: List<com.trackmaster.app.data.GeoFence>): List<Geofence> {
        val coreFences = oldFences.map { GeoMath.buildEngineGeofence(it) }
        return applyParentMap(coreFences)
    }

    /**
     * 对已有的核心 Geofence 列表应用 parent map
     */
    fun applyParentMap(fences: List<Geofence>): List<Geofence> {
        val parentMap = getParentMap()
        if (parentMap.isEmpty()) {
            return GeoMath.populateDepth(fences)
        }

        // 应用 parent map
        val mapped = fences.map { fence ->
            val parentId = parentMap[fence.id]
            if (parentId != null && fences.any { it.id == parentId }) {
                fence.copy(parentId = parentId)
            } else {
                fence
            }
        }

        return GeoMath.populateDepth(mapped)
    }

    /**
     * 构建围栏树结果
     */
    fun buildTree(oldFences: List<com.trackmaster.app.data.GeoFence>): FenceTreeResult {
        val fences = loadFenceTree(oldFences)
        return buildFenceTree(fences)
    }

    /**
     * 检查设置 parentId 是否会形成循环
     *
     * @return true = 安全（不会循环）
     */
    fun isSafeParent(fenceId: String, parentId: String, fences: List<Geofence>): Boolean {
        if (fenceId == parentId) return false
        val fenceMap = fences.associateBy { it.id }
        var cur = parentId
        val visited = mutableSetOf<String>()
        while (cur != fenceId) {
            if (!visited.add(cur)) return false // 已访问过，有循环
            cur = fenceMap[cur]?.parentId ?: return true // 到达顶级
        }
        return false // cur == fenceId，形成循环
    }

}
