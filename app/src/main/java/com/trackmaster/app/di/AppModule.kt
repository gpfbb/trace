package com.trackmaster.app.di

import android.database.sqlite.SQLiteOpenHelper
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.GeoFenceDatabaseHelper
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.core.EngineHolder
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.core.RepositoryHolder
import com.trackmaster.app.storage.SqliteStorageAdapter
import com.trackmaster.app.storage.StorageAdapter
import com.trackmaster.app.utils.ThrottleConfig
import com.trackmaster.app.viewmodel.HomeViewModel
import com.trackmaster.app.viewmodel.MainViewModel
import com.trackmaster.app.viewmodel.MapViewModel
import com.trackmaster.app.viewmodel.SettingsViewModel
import com.trackmaster.app.viewmodel.StatisticsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin 依赖注入模块
 *
 * 提供应用级别的依赖配置：
 * - Application 实例
 * - 数据库相关单例
 * - 仓库层单例
 * - ViewModel 实例
 */
val appModule = module {

    // ============================================================
    // Application (单例)
    // ============================================================

    /**
     * Application 实例
     * 用于访问全局状态和服务
     */
    single<TrackMasterApplication> {
        TrackMasterApplication.instance
    }

    // ============================================================
    // 数据库层 (单例)
    // ============================================================

    /**
     * SQLite 存储适配器
     * 负责将 StorageAdapter 接口实现委托给 GeoFenceDatabaseHelper
     */
    single<SQLiteOpenHelper> { GeoFenceDatabaseHelper(get()) }
    single<GeoFenceDatabaseHelper> { get<SQLiteOpenHelper>() as GeoFenceDatabaseHelper }
    single<StorageAdapter> {
        SqliteStorageAdapter(get())
    }

    // ============================================================
    // Holder 层 (单例)
    // ============================================================

    single { RepositoryHolder(get()) }
    single { get<RepositoryHolder>().repository }

    // FenceTreeManager needs Context, use applicationContext
    single { FenceTreeManager(androidContext()) }

    single { AppPreferences(get()) }
    single {
        val prefs: AppPreferences = get()
        ThrottleConfig(
            driftThresholdM = prefs.driftThreshold,
            stationaryMinDistanceM = prefs.stationaryThreshold,
            boundaryBufferM = prefs.boundaryBuffer
        )
    }

    single {
        EngineHolder(
            repository = get<RepositoryHolder>().repository,
            throttleConfig = get(),
            appPreferences = get()
        )
    }

    // ============================================================
    // ViewModel (每次请求创建新实例)
    // ============================================================

    /**
     * 主 ViewModel - 协调位置、围栏和跨屏幕状态
     */
    viewModel {
        MainViewModel(
            application = get(),
            repository = get()
        )
    }

    /**
     * 地图 ViewModel - 管理地图绘制模式、覆盖物和编辑状态
     */
    viewModel {
        MapViewModel(
            application = get()
        )
    }

    /**
     * 首页 ViewModel - 管理仪表盘数据聚合
     */
    viewModel {
        HomeViewModel(
            application = get()
        )
    }

    /**
     * 设置 ViewModel - 管理设置状态
     */
    viewModel {
        SettingsViewModel(
            application = get()
        )
    }

    /**
     * 统计 ViewModel - 管理统计数据
     */
    viewModel {
        StatisticsViewModel(
            application = get()
        )
    }
}
