package com.trackmaster.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 停留记录的围栏名解析。
 *
 * 背景：旧版本把 fenceId 当成 fenceName 写进 `stays` / `daily_stays`，
 * 界面上就出现了 `bf64623f-7995-4af7-9f27-b4b12feeffed` 这样的 UUID。
 * 写入侧已修，但已经落库的历史数据还得靠读取时兜底。
 */
class StayRecordNameResolverTest {

    private val names = mapOf(
        "fence-1" to "公司",
        "fence-2" to "家"
    )

    @Test
    fun `正常存下来的名字原样使用`() {
        assertEquals(
            "公司",
            StayRecordNameResolver.resolve("公司", "fence-1", names)
        )
    }

    @Test
    fun `名字等于 id 时回退到围栏表里的当前名字`() {
        // 这是用户实际看到的 bug：名称列存的是 UUID
        assertEquals(
            "公司",
            StayRecordNameResolver.resolve("fence-1", "fence-1", names)
        )
    }

    @Test
    fun `围栏名是空串时也回退`() {
        assertEquals(
            "家",
            StayRecordNameResolver.resolve("", "fence-2", names)
        )
    }

    @Test
    fun `围栏已被删除且存的是 id 时退回显示 id`() {
        // 没别的办法：名字已无从得知，显示 id 好过显示空白
        assertEquals(
            "deleted-fence",
            StayRecordNameResolver.resolve("deleted-fence", "deleted-fence", names)
        )
    }

    @Test
    fun `围栏已删除但存的是真名字时保留原来的名字`() {
        // 历史记录应当保留当时的名字，即使围栏已经没了
        assertEquals(
            "老公司",
            StayRecordNameResolver.resolve("老公司", "deleted-fence", names)
        )
    }

    @Test
    fun `存的是真名字时不会被当前名字覆盖`() {
        // 围栏后来改过名，旧记录仍显示当时的名字
        assertEquals(
            "旧名字",
            StayRecordNameResolver.resolve("旧名字", "fence-1", names)
        )
    }
}
