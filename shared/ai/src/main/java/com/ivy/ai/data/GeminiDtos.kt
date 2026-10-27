package com.ivy.ai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire types for Gemini's generateContent endpoint.
 * See https://ai.google.dev/api/generate-content
 */
@Serializable
internal data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
internal data class Content(
    val parts: List<Part>,
    val role: String? = null,
)

@Serializable
internal data class Part(
    val text: String,
)

@Serializable
internal data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseSchema: JsonElement? = null,
    val temperature: Double? = null,
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
internal data class Candidate(
    val content: Content? = null,
)
