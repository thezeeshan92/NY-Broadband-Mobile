package com.nybroadband.mobile.di

import com.nybroadband.mobile.engine.Ndt7SpeedTestEngine
import com.nybroadband.mobile.engine.SpeedTestEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [SpeedTestEngine] to [Ndt7SpeedTestEngine] — the real M-Lab NDT7 implementation.
 *
 * To switch engine (e.g. for testing): replace [Ndt7SpeedTestEngine] here.
 * No other production code needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindSpeedTestEngine(impl: Ndt7SpeedTestEngine): SpeedTestEngine
}
