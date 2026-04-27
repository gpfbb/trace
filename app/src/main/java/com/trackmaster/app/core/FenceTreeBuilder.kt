package com.trackmaster.app.core

import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.utils.geometry.TAG
import com.trackmaster.app.utils.geometry.isFenceContainedByFence

/**
 * 围栏树构建结果
 */
data class FenceTreeResult(
    val fenceMap: Map<String, Geofence>,
    val childrenMap: Map<String, List<Geofence>>,
    val leafFences: List<Geofence>,
    /** 按深度降序排列的叶子围栏 */
    val leavesByDepth: List<Geofence>,
)

/**
 * 验证围栏树的完整性
 * @return 验证结果，包含错误列表
 */
data class TreeValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

/** 获取围栏深度（0 = 顶级，基于parentId） */
fun getFenceDepth(fenceId: String, fenceMap: Map<String, Geofence>): Int {
    var d = 0
    var cur = fenceMap[fenceId]
    while (cur?.parentId != null) {
        d++
        cur = fenceMap[cur.parentId]
    }
    return d
}

/** 为围栏列表填充 depth 字段 - 自动计算嵌套关系 */
fun populateDepth(fences: List<Geofence>): List<Geofence> {
    if (fences.isEmpty()) return emptyList()

    // 1. 首先用 parentId 计算已有的深度
    val map = fences.associateBy { it.id }
    val depthByParent = fences.associate { it.id to getFenceDepth(it.id, map) }

    // 2. 自动计算几何嵌套：检查每个围栏是否被其他围栏包含
    // 如果围栏A包含围栏B，则B的深度 = A的深度 + 1
    val containmentDepth = calculateContainmentDepth(fences)

    // 3. 取两者的最大值（parentId深度 和 几何嵌套深度）
    // 几何嵌套优先级更高，因为用户可能没设置parent但围栏确实嵌套
    return fences.map { fence ->
        val parentDepth = depthByParent[fence.id] ?: 0
        val containDepth = containmentDepth[fence.id] ?: 0
        // 如果几何嵌套深度更大，覆盖parentId的深度
        val finalDepth = if (containDepth > parentDepth) containDepth else parentDepth
        fence.copy(depth = finalDepth)
    }
}

/**
 * 基于几何包含关系计算围栏深度
 * 如果围栏A包含围栏B，则B的深度 = A的深度 + 1
 * 叶子围栏（不被任何围栏包含）深度最大
 */
private fun calculateContainmentDepth(fences: List<Geofence>): Map<String, Int> {
    if (fences.isEmpty()) return emptyMap()

    val result = mutableMapOf<String, Int>()

    // 初始化：所有围栏深度为0
    fences.forEach { result[it.id] = 0 }

    // 比较每对围栏，检查是否有包含关系
    for (fenceA in fences) {
        for (fenceB in fences) {
            if (fenceA.id == fenceB.id) continue

            // 检查 B 是否在 A 内部
            if (isFenceContainedByFence(fenceB, fenceA)) {
                // B 在 A 内部，所以 B 的深度应该 > A 的深度
                val currentBDepth = result[fenceB.id] ?: 0
                val currentADepth = result[fenceA.id] ?: 0
                // B 的深度至少是 A 的深度 + 1
                result[fenceB.id] = maxOf(currentBDepth, currentADepth + 1)
            }
        }
    }

    return result
}

/** 验证围栏树完整性 */
fun validateFenceTree(fences: List<Geofence>): TreeValidationResult {
    val errors = mutableListOf<String>()
    val fenceMap = fences.associateBy { it.id }

    // 检查循环引用
    for (fence in fences) {
        val visited = mutableSetOf<String>()
        var currentId: String? = fence.id

        // 沿着父链向上检查
        while (currentId != null) {
            if (!visited.add(currentId)) {
                errors.add("循环引用检测到: 围栏 $currentId 在父链中重复出现")
                break
            }

            val currentFence = fenceMap[currentId]
            if (currentFence == null) {
                errors.add("引用不存在的围栏: $currentId")
                break
            }

            currentId = currentFence.parentId
        }

        // 检查父级是否存在（如果指定了父级）
        if (fence.parentId != null && fenceMap[fence.parentId] == null) {
            errors.add("围栏 ${fence.id} 引用了不存在的父级 ${fence.parentId}")
        }

        // 检查自引用
        if (fence.parentId == fence.id) {
            errors.add("围栏 ${fence.id} 引用了自身作为父级")
        }
    }

    // 检查深度一致性
    val depths = mutableMapOf<String, Int>()
    fun calculateDepth(fenceId: String): Int {
        if (fenceId in depths) return depths[fenceId] ?: 0

        val fence = fenceMap[fenceId] ?: return 0
        if (fence.parentId == null) {
            depths[fenceId] = 0
            return 0
        }

        val parentDepth = calculateDepth(fence.parentId)
        val depth = parentDepth + 1
        depths[fenceId] = depth
        return depth
    }

    for (fence in fences) {
        calculateDepth(fence.id)
    }

    // 检查深度字段是否与计算一致
    for (fence in fences) {
        val calculatedDepth = depths[fence.id] ?: 0
        if (fence.depth != calculatedDepth) {
            errors.add("围栏 ${fence.id} 的深度字段不一致: 存储=${fence.depth}, 计算=$calculatedDepth")
        }
    }

    return TreeValidationResult(errors.isEmpty(), errors)
}

/** 构建围栏父子映射 */
fun buildFenceTree(fences: List<Geofence>, validate: Boolean = true): FenceTreeResult {
    val startTime = System.currentTimeMillis()

    // 可选验证
    if (validate) {
        val validation = validateFenceTree(fences)
        if (!validation.isValid) {
            AppLog.w(TAG, "围栏树验证失败，但继续构建: ${validation.errors.joinToString(", ")}")
        } else {
            AppLog.d(TAG, "围栏树验证通过: ${fences.size} 个围栏")
        }
    }

    val fenceMap = fences.associateBy { it.id }
    val childrenMap = mutableMapOf<String, MutableList<Geofence>>()

    for (f in fences) {
        val pid = f.parentId
        if (pid != null) {
            childrenMap.getOrPut(pid) { mutableListOf() }.add(f)
        }
    }

    // 叶子 = 没有子级的围栏
    val leafFences = fences.filter { it.id !in childrenMap }
    val leavesByDepth = leafFences.sortedByDescending { it.depth }

    // 收集统计信息
    val maxDepth = leavesByDepth.firstOrNull()?.depth ?: 0
    val avgChildren = if (childrenMap.isNotEmpty()) {
        childrenMap.values.sumOf { it.size }.toDouble() / childrenMap.size
    } else 0.0
    val leafCount = leafFences.size
    val totalWithParent = fences.count { it.parentId != null }

    val buildTime = System.currentTimeMillis() - startTime

    AppLog.d(TAG, "围栏树构建完成: 总数=${fences.size}, 叶子=$leafCount, 最大深度=$maxDepth, 平均子节点数=${"%.2f".format(avgChildren)}, 有父级的围栏=$totalWithParent, 耗时=${buildTime}ms")

    return FenceTreeResult(
        fenceMap = fenceMap,
        childrenMap = childrenMap,
        leafFences = leafFences,
        leavesByDepth = leavesByDepth,
    )
}
