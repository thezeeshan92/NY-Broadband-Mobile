package com.nybroadband.mobile.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.nybroadband.mobile.data.local.db.AppDatabase
import com.nybroadband.mobile.data.local.db.AppDatabase.Companion.MIGRATION_1_2
import com.nybroadband.mobile.data.local.db.AppDatabase.Companion.MIGRATION_2_3
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * AppModule wires the Room database, all DAOs, OkHttpClient, and Moshi.
 *
 * OkHttpClient configuration for NDT7 WebSocket streams:
 *   connectTimeout = 10 s  (fail fast if server unreachable)
 *   readTimeout    = 0     (disable — download stream must run for full test duration)
 *   writeTimeout   = 0     (disable — upload stream sends continuously)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // required: NDT7 download stream has no idle timeout
        .writeTimeout(0, TimeUnit.SECONDS)  // required: NDT7 upload stream sends continuously
        .retryOnConnectionFailure(false)    // NDT7 tests must not be retried automatically
        .build()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
