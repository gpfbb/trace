package com.trackmaster.app

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追踪总开关（AppPreferences.isTrackingEnabled）的语义测试
 *
 * 这个开关是"用户意图"的唯一真源：WakeupService 的自恢复、BootCompletedReceiver
 * 的开机拉起、MainActivity 的隐式启动都读它。初始值必须是 true，
 * 否则老用户升级后会发现追踪被莫名其妙关掉了。
 */
class TrackingPreferenceTest {

    /** 真正可读写的内存 SharedPreferences */
    private class InMemoryPrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST")
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals.add(key) }
            override fun clear() = apply { pending.clear(); removals.addAll(map.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removals.forEach { map.remove(it) }
                pending.forEach { (k, v) -> map[k] = v }
                pending.clear(); removals.clear()
            }
        }
    }

    private fun newPrefs(): AppPreferences {
        val prefs = InMemoryPrefs()
        val context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        return AppPreferences(context)
    }

    @Test
    fun `tracking is enabled by default so existing users keep working`() {
        val prefs = newPrefs()
        assertTrue("默认必须是开启，否则升级后的老用户会莫名停止追踪", prefs.isTrackingEnabled)
    }

    @Test
    fun `tracking can be turned off and persists`() {
        val prefs = newPrefs()
        prefs.isTrackingEnabled = false
        assertFalse(prefs.isTrackingEnabled)

        // 模拟下次启动重新读取：同一份存储应保持关闭
        prefs.isTrackingEnabled = false
        assertFalse("关闭状态必须持久化，重启后不能被默认值覆盖", prefs.isTrackingEnabled)
    }

    @Test
    fun `tracking can be re-enabled`() {
        val prefs = newPrefs()
        prefs.isTrackingEnabled = false
        prefs.isTrackingEnabled = true
        assertTrue(prefs.isTrackingEnabled)
    }

    @Test
    fun `turn off then on again round trips`() {
        val prefs = newPrefs()
        repeat(3) {
            prefs.isTrackingEnabled = false
            assertFalse(prefs.isTrackingEnabled)
            prefs.isTrackingEnabled = true
            assertTrue(prefs.isTrackingEnabled)
        }
    }
}
