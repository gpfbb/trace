package com.trackmaster.app.di

import android.content.Context
import com.trackmaster.app.data.database.AppDatabase
import com.trackmaster.app.data.repository.TrackingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideRepository(db: AppDatabase): TrackingRepository {
        return TrackingRepository(
            db.trackSessionDao(),
            db.trackPointDao(),
            db.stayPointDao(),
            db.geofenceDao(),
            db.geofenceEventDao()
        )
    }
}
