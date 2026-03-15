package com.nybroadband.mobile.di

import android.content.Context
import androidx.room.Room
import com.nybroadband.mobile.data.local.db.AppDatabase
import com.nybroadband.mobile.data.local.db.AppDatabase.Companion.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule wires the Room database and all DAOs.
 * Network, Firebase, and Location modules are added in subsequent tasks
 * once their respective layers are implemented.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DB_NAME
        )
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration() // safety net for dev; remove before v1 public release
            .build()

    @Provides
    fun provideMeasurementDao(db: AppDatabase) = db.measurementDao()

    @Provides
    fun provideDeadZoneDao(db: AppDatabase) = db.deadZoneDao()

    @Provides
    fun provideSyncQueueDao(db: AppDatabase) = db.syncQueueDao()

    @Provides
    fun provideServerDefinitionDao(db: AppDatabase) = db.serverDefinitionDao()

    @Provides
    fun provideRemoteConfigCacheDao(db: AppDatabase) = db.remoteConfigCacheDao()
}
