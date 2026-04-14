package com.trackmaster.app.core

import android.content.Context
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.CategoryManager
import com.trackmaster.app.DataManager
import com.trackmaster.app.PersistentStateManager
import com.trackmaster.app.StatisticsManager
import com.trackmaster.app.StayDurationManager

/**
 * Application 级别管理器的容器
 *
 * 将所有 Context 管理器集中管理，由 TrackMasterApplication 在 onCreate() 中初始化。
 * 所有管理器均使用 Application 作为 Context（生命周期等于 Application）。
 *
 * 使用方式（ViewModel 等）：
 *   val managers = app.managersHolder  // via TrackMasterApplication.managersHolder
 */
class ManagersHolder {
    lateinit var categoryManager: CategoryManager
        private set
    lateinit var statisticsManager: StatisticsManager
        private set
    lateinit var dataManager: DataManager
        private set
    lateinit var stayDurationManager: StayDurationManager
        private set
    lateinit var persistentStateManager: PersistentStateManager
        private set
    lateinit var appPreferences: AppPreferences
        private set

    fun init(context: Context) {
        categoryManager = CategoryManager(context)
        statisticsManager = StatisticsManager(context)
        dataManager = DataManager(context)
        stayDurationManager = StayDurationManager(context)
        persistentStateManager = PersistentStateManager(context)
        appPreferences = AppPreferences(context)
    }
}
