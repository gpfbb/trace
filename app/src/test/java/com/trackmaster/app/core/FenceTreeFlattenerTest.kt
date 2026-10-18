package com.trackmaster.app.core

import com.trackmaster.app.data.GeoFence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏列表的父子树扁平化。
 *
 * 这段逻辑决定"用户在围栏列表里能看到哪些围栏、缩进多少"。
 * 它必须满足两条硬性要求：
 * 1. 任何输入都不能崩溃（父子数据可能来自导入、来自重新计算，可能是环）
 * 2. 任何围栏都不能凭空消失（用户看不到就删不掉）
 */
class FenceTreeFlattenerTest {

    @Test
    fun `没有父子关系时按顶级平铺`() {
        val fences = listOf(fence("a"), fence("b"), fence("c"))
        val result = FenceTreeFlattener.flatten(fences, emptyMap(), emptySet())

        assertEquals(3, result.size)
        assertTrue("全部应为深度 0", result.all { it.second == 0 })
    }

    @Test
    fun `未展开的父围栏不显示子节点`() {
        val fences = listOf(fence("parent"), fence("child"))
        val parents = mapOf("child" to "parent")

        val collapsed = FenceTreeFlattener.flatten(fences, parents, expanded = emptySet())
        assertEquals("收起时只应看到父围栏", 1, collapsed.size)
        assertEquals("parent", collapsed[0].first.id)
    }

    @Test
    fun `展开的父围栏显示子节点且深度递增`() {
        val fences = listOf(fence("parent"), fence("child"))
        val parents = mapOf("child" to "parent")

        val expanded = FenceTreeFlattener.flatten(fences, parents, expanded = setOf("parent"))
        assertEquals(2, expanded.size)
        assertEquals("parent", expanded[0].first.id)
        assertEquals(0, expanded[0].second)
        assertEquals("child", expanded[1].first.id)
        assertEquals(1, expanded[1].second)
    }

    @Test
    fun `多级嵌套按深度递进`() {
        val fences = listOf(fence("a"), fence("b"), fence("c"))
        val parents = mapOf("b" to "a", "c" to "b")

        val result = FenceTreeFlattener.flatten(fences, parents, expanded = setOf("a", "b"))
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), result.map { it.first.id to it.second })
    }

    // ------------------------------------------------------------
    // 以下两条是原实现会出问题的地方
    // ------------------------------------------------------------

    @Test
    fun `父子引用成环不会栈溢出`() {
        // a 的父是 b，b 的父是 a。原实现从 parentId==null 开始遍历，
        // 两个节点都不可达，于是列表里两个围栏全部消失。
        val fences = listOf(fence("a"), fence("b"))
        val parents = mapOf("a" to "b", "b" to "a")

        val result = FenceTreeFlattener.flatten(fences, parents, expanded = setOf("a", "b"))

        assertEquals("成环时两个围栏都必须仍然可见", 2, result.size)
    }

    @Test
    fun `父围栏已被删除时子围栏仍可见`() {
        // child 指向一个不存在的 parent。原实现会把它挂在
        // childrenMap["parent"] 下，而从 null 开始遍历永远到不了这里。
        val fences = listOf(fence("child"))
        val parents = mapOf("child" to "deleted-parent")

        val result = FenceTreeFlattener.flatten(fences, parents, expanded = emptySet())

        assertEquals("孤立节点必须提升为顶级节点显示", 1, result.size)
        assertEquals("child", result[0].first.id)
        assertEquals(0, result[0].second)
    }

    @Test
    fun `自己指向自己不会死循环`() {
        val fences = listOf(fence("a"))
        val parents = mapOf("a" to "a")

        val result = FenceTreeFlattener.flatten(fences, parents, expanded = setOf("a"))

        assertEquals(1, result.size)
    }

    @Test
    fun `空列表返回空`() {
        assertTrue(FenceTreeFlattener.flatten(emptyList(), emptyMap(), emptySet()).isEmpty())
    }

    // ------------------------------------------------------------

    private fun fence(id: String) = GeoFence(
        id = id,
        name = id,
        type = GeoFenceType.CIRCLE
    )
}
