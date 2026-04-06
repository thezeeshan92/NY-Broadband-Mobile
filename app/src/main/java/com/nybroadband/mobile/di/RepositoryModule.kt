package com.nybroadband.mobile.di

import com.nybroadband.mobile.data.repository.MeasurementRepositoryImpl
import com.nybroadband.mobile.data.repository.SyncRepositoryImpl
import com.nybroadband.mobile.domain.repository.MeasurementRepository
import com.nybroadband.mobile.domain.repository.SyncRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMeasurementRepository(impl: MeasurementRepositoryImpl): MeasurementRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository
}
