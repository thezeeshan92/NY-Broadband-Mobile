package com.nybroadband.mobile.di

import com.nybroadband.mobile.data.repository.DeadZoneRepositoryImpl
import com.nybroadband.mobile.domain.repository.DeadZoneRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeadZoneModule {

    @Binds
    @Singleton
    abstract fun bindDeadZoneRepository(impl: DeadZoneRepositoryImpl): DeadZoneRepository
}
