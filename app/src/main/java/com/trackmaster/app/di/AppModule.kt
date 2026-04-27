package com.trackmaster.app.di

import com.trackmaster.app.AppPreferences
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.core.EngineHolder
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.core.RepositoryHolder
import com.trackmaster.app.data.dao.CategoryDao
import com.trackmaster.app.data.dao.DailyStayDao
import com.trackmaster.app.data.dao.EventDao
import com.trackmaster.app.data.dao.FenceDao
import com.trackmaster.app.data.dao.StayDao
import com.trackmaster.app.data.database.DatabaseFacade
import com.trackmaster.app.data.database.DatabaseManager
import com.trackmaster.app.data.database.FenceExporter
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

    single<TrackMasterApplication> {
        TrackMasterApplication.instance
    }

    // ============================================================
    // 数据库层 (单例)
    // ============================================================

    /** 数据库管理器 — 持有 SQLiteOpenHelper，管理 schema 和迁移 */
    single { DatabaseManager(get()) }

    /** DAO 层 — 每个表一个 DAO，各自维护缓存 */
    single { FenceDao(get()) }
    single { CategoryDao(get()) }
    single { EventDao(get()) }
    single { StayDao(get()) }
    single { DailyStayDao(get()) }

    /** 导出工具 */
    single { FenceExporter(get()) }

    /** 数据库门面 — 兼容旧代码的统一入口，Phase 6 后逐步移除 */
    single { DatabaseFacade(get(), get(), get(), get(), get(), get(), get()) }

    /** SQLiteOpenHelper 引用 — 供 SqliteStorageAdapter 使用 */
    single<android.database.sqlite.SQLiteOpenHelper> { get<DatabaseManager>() }

    /** StorageAdapter — 引擎层存储适配器 */
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

    viewModel {
        MainViewModel(
            application = get(),
            repository = get()
        )
    }

    viewModel {
        MapViewModel(
            application = get()
        )
    }

    viewModel {
        HomeViewModel(
            application = get()
        )
    }

    viewModel {
        SettingsViewModel(
            application = get()
        )
    }

    viewModel {
        StatisticsViewModel(
            application = get()
        )
    }
}
