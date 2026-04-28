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
import com.trackmaster.app.data.repository.AppFenceRepository
import com.trackmaster.app.data.repository.AppSessionRepository
import com.trackmaster.app.data.repository.FenceRepository
import com.trackmaster.app.data.repository.SessionRepository
import com.trackmaster.app.storage.SqliteStorageAdapter
import com.trackmaster.app.storage.StorageAdapter
import com.trackmaster.app.throttle.ThrottleConfig
import com.trackmaster.app.viewmodel.HomeViewModel
import com.trackmaster.app.viewmodel.MapViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

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

    single { DatabaseManager(get()) }

    single { FenceDao(get()) }
    single { CategoryDao(get()) }
    single { EventDao(get()) }
    single { StayDao(get()) }
    single { DailyStayDao(get()) }

    single { FenceExporter(get()) }

    single { DatabaseFacade(get(), get(), get(), get(), get(), get(), get()) }

    single<android.database.sqlite.SQLiteOpenHelper> { get<DatabaseManager>() }

    single<StorageAdapter> {
        SqliteStorageAdapter(get())
    }

    // ============================================================
    // Repository 层 (单例)
    // ============================================================

    single<FenceRepository> { AppFenceRepository(get()) }
    single<SessionRepository> { AppSessionRepository(get()) }

    // ============================================================
    // Holder 层 (单例)
    // ============================================================

    single { RepositoryHolder(get()) }
    single { get<RepositoryHolder>().repository }

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
    // ViewModel
    // ============================================================

    viewModel {
        MapViewModel(
            fenceRepo = get()
        )
    }

    viewModel {
        HomeViewModel(
            sessionRepo = get()
        )
    }

}
