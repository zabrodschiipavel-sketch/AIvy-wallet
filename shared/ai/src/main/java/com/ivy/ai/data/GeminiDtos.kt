package com.ivy.ai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun geminiWireJson(base: Json): Json = Json(base) {
    explicitNulls = false
}

internal fun GeminiResponse.textOrNull(): String? =
    candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

/**
 * Wire types for Gemini's generateContent endpoint.
 * See https://ai.google.dev/api/generate-content
 */
@Serializable
internal data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig,
)

@Serializable
internal data class Content(
    val parts: List<Part>,
)

@Serializable
internal data class Part(
    val text: String,
)

@Serializable
internal data class GenerationConfig(
    val responseMimeType: String,
    val responseSchema: JsonElement,
    val temperature: Double,
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<Candidate>?,
)

@Serializable
internal data class Candidate(
    val content: Content?,
)
