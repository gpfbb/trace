package com.trackmaster.app.core

import com.trackmaster.app.data.GeoFence

/**
 * 把"父子关系 + 展开状态"压平成一个可直接渲染的列表。
 *
 * 输出里每个元素带一个深度值，渲染方按深度做缩进。
 *
 * ## 为什么单独成文件
 *
 * 这段逻辑原来嵌在 `MapScreen` 的 `FenceListDialog` 里，是个局部递归函数。
 * 抽出来之后能单独测试，并且顺手修掉了两个会在真机上出问题的地方：
 *
 * 1. **父子引用成环会无限递归**。原实现没有任何防环措施，一旦
 *    `FenceTreeManager` 里出现 A→B→A（重新计算父子关系、手工导入数据时
 *    都可能出现），递归直接栈溢出崩溃。
 *
 * 2. **父围栏不存在的节点会被静默丢弃**。原实现只从 `parentId == null`
 *    开始遍历，如果某个围栏的 parentId 指向一个已被删除的围栏，它就
 *    永远遍历不到——用户在列表里看不到这个围栏，也删不掉。
 */
object FenceTreeFlattener {

    /**
     * @param fences 全部围栏
     * @param parentMap 围栏 id → 父围栏 id
     * @param expanded 已展开的围栏 id 集合；未展开的围栏不显示子节点
     * @return 扁平化后的 (围栏, 深度) 列表，按显示顺序排列
     */
    fun flatten(
        fences: List<GeoFence>,
        parentMap: Map<String, String>,
        expanded: Set<String>
    ): List<Pair<GeoFence, Int>> {
        if (fences.isEmpty()) return emptyList()

        val fenceById = fences.associateBy { it.id }

        // 父围栏必须真实存在，否则当作顶级节点处理。
        // 这样被删除父围栏后的孤儿节点仍然可见、可操作。
        fun effectiveParentId(fenceId: String): String? {
            val declared = parentMap[fenceId] ?: return null
            if (declared == fenceId) return null          // 自己指向自己
            if (declared !in fenceById) return null       // 父围栏已被删除
            return declared
        }

        val childrenMap = mutableMapOf<String?, MutableList<String>>()
        for (fence in fences) {
            childrenMap.getOrPut(effectiveParentId(fence.id)) { mutableListOf() }
                .add(fence.id)
        }

        // 第一遍：只看"从根出发能不能走到"，不看展开状态。
        //
        // 这一步用来找出真正脱离树的节点（成环、或整棵子树挂在环上）。
        // 必须和展开状态分开算——否则"父围栏收起时隐藏的子节点"
        // 会被误判成孤儿，然后在收起状态下被错误地显示出来。
        val reachable = mutableSetOf<String>()
        run {
            val queue = ArrayDeque(childrenMap[null].orEmpty())
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!reachable.add(id)) continue   // 成环时在这里终止
                childrenMap[id]?.let { queue.addAll(it) }
            }
        }

        val result = mutableListOf<Pair<GeoFence, Int>>()
        // 记录已输出节点，兜住"仍然成环"的极端情况
        val emitted = mutableSetOf<String>()

        fun addVisible(parentId: String?, depth: Int) {
            val children = childrenMap[parentId] ?: return
            for (childId in children) {
                if (!emitted.add(childId)) continue    // 已输出过，说明成环，跳过
                val child = fenceById[childId] ?: continue
                result.add(child to depth)
                if (childId in expanded) {
                    addVisible(childId, depth + 1)
                }
            }
        }

        addVisible(null, 0)

        // 第二遍：把从根不可达的节点（成环、孤儿）按顶级节点补上，
        // 保证"所有围栏都能在列表里被看到"——看不到就删不掉。
        for (fence in fences) {
            if (fence.id in reachable || fence.id in emitted) continue
            if (!emitted.add(fence.id)) continue
            result.add(fence to 0)
            if (fence.id in expanded) addVisible(fence.id, 1)
        }

        return result
    }
}
