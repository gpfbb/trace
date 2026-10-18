package com.trackmaster.app.data

/**
 * 解析停留记录里该显示的围栏名。
 *
 * ## 背景
 *
 * 旧版本的 `FenceProcessingHolder.handleFenceExit` 把 `fenceId` 当成
 * `fenceName` 传给下游，于是 `stays` 和 `daily_stays` 两张表的名称列
 * 存进去的是一串 UUID。首页"本月常去"和统计页直接显示这个字段，
 * 用户看到的就是 `bf64623f-7995-4af7-9f27-b4b12feeffed`。
 *
 * 写入侧已经修好，但**已经落库的旧数据还在**。与其写一条会改动用户
 * 历史记录的迁移脚本（风险高、不可逆），不如在读取时做一次显示层的兼容：
 * 只有当存下来的"名字"恰好等于 fenceId 时，才认为它是坏的，
 * 用围栏表里的当前名字替代。
 *
 * ## 为什么不一律按 fenceId 查当前名字
 *
 * 历史记录应该保留**当时的名字**——围栏后来改名了，旧记录不该跟着变。
 * 所以只在确认存的是 id（即数据坏了）时才回退到当前名字。
 */
object StayRecordNameResolver {

    /**
     * @param storedName 记录里存的名称（可能是坏的 id）
     * @param fenceId 记录所属围栏的 id
     * @param nameById 围栏 id → 当前名称
     */
    fun resolve(
        storedName: String?,
        fenceId: String,
        nameById: Map<String, String>
    ): String {
        // 存的就是 id，说明是旧版本写坏的
        if (storedName.isNullOrBlank() || storedName == fenceId) {
            return nameById[fenceId] ?: storedName?.takeIf { it != fenceId } ?: fenceId
        }
        return storedName
    }
}
