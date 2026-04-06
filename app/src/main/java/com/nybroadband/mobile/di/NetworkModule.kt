package com.nybroadband.mobile.di

import com.nybroadband.mobile.BuildConfig
import com.nybroadband.mobile.data.remote.NyuBroadbandApi
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provides the Retrofit stack for the NYU Broadband backend API.
 *
 * A separate [OkHttpClient] qualified as "api" is used — distinct from the
 * NDT7 client in [AppModule] which requires read/write timeout = 0 for
 * streaming WebSocket tests.
 *
 * Logging: BODY level in debug builds only; NONE in release to avoid
 * leaking any measurement payload content to logcat in production.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @Named("api")
    fun provideApiOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        @Named("api") client: OkHttpClient,
        moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideNyuBroadbandApi(retrofit: Retrofit): NyuBroadbandApi =
        retrofit.create(NyuBroadbandApi::class.java)
}
