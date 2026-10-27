package com.ivy.ai.di

import com.ivy.ai.data.GeminiApiKeyStore
import com.ivy.ai.data.GeminiApiKeyStoreImpl
import com.ivy.ai.data.GeminiClient
import com.ivy.ai.data.GeminiClientImpl
import com.ivy.ai.data.geminiWireJson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiBindings {
    @Binds
    @Singleton
    abstract fun bindKeyStore(impl: GeminiApiKeyStoreImpl): GeminiApiKeyStore

    @Binds
    @Singleton
    abstract fun bindGeminiClient(impl: GeminiClientImpl): GeminiClient
}

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    private const val IO_TIMEOUT_MILLIS = 60_000L
    private const val CONNECT_TIMEOUT_MILLIS = 15_000L

    /**
     * Dedicated HttpClient for Gemini — separate from any app-wide client so
     * timeouts/logging here don't leak into other call sites.
     */
    @Provides
    @Singleton
    @AiHttpClient
    fun provideGeminiHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(geminiWireJson(json)) }
        install(HttpTimeout) {
            requestTimeoutMillis = IO_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = IO_TIMEOUT_MILLIS
        }
        install(Logging) { level = LogLevel.NONE } // flip to INFO for debugging
    }
}
