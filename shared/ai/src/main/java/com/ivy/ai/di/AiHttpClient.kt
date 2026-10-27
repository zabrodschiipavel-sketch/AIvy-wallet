package com.ivy.ai.di

import javax.inject.Qualifier

/**
 * Qualifier for the AI-dedicated [io.ktor.client.HttpClient].
 * Keeps it distinct from the app-wide HttpClient (shared/data/core) so
 * timeouts, logging level and engine choice here don't bleed into other call sites.
 *
 * The Gemini key travels in the URL query string — we deliberately keep
 * Logging at NONE on this client to avoid leaking it into Timber/Logcat.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiHttpClient
