package com.nybroadband.mobile.di

import com.nybroadband.mobile.engine.PlaceholderSpeedTestEngine
import com.nybroadband.mobile.engine.SpeedTestEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [SpeedTestEngine] to [PlaceholderSpeedTestEngine] for the MVP build.
 *
 * PLACEHOLDER — when a real engine (NDT7, Ookla, etc.) is ready, swap
 * [PlaceholderSpeedTestEngine] for the real implementation here.
 * No other production code needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindSpeedTestEngine(impl: PlaceholderSpeedTestEngine): SpeedTestEngine
}
