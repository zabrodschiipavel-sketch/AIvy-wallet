package com.ivy.ai.data

import com.ivy.ai.di.AiHttpClient
import com.ivy.ai.domain.ParsedTransaction
import com.ivy.ai.domain.TransactionTypeGuess
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Thin wrapper over Gemini's REST API. Only knows how to call the model;
 * the prompting strategy lives in [ParseTransactionUseCase].
 */
interface GeminiClient {
    /**
     * Generic structured-output call.
     * @return the raw JSON string returned by the model (already extracted from candidates[0]).
     * @throws GeminiException on any non-2xx / network / parse failure.
     */
    suspend fun generateStructured(
        prompt: String,
        schema: JsonObject,
        model: String = DEFAULT_MODEL,
    ): String

    /**
     * Trivial round-trip that verifies the configured key + network reach the model.
     * Used by the Settings "Test connection" button. Throws on failure.
     */
    suspend fun ping(model: String = DEFAULT_MODEL)

    companion object {
        const val DEFAULT_MODEL: String = "gemini-3.1-flash-lite"
    }
}

class GeminiException(
    message: String,
    val reason: Reason = Reason.Unknown,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Coarse failure category so callers can show an actionable message. */
    enum class Reason {
        /** No API key configured — the user must set one in Settings → AI. */
        MissingApiKey,

        /** Network/transport failure reaching the Gemini endpoint. */
        Network,

        /** The call succeeded but the model returned no usable content. */
        EmptyResponse,

        /** Anything else (unexpected HTTP status, serialization, etc.). */
        Unknown,
    }
}

class GeminiClientImpl @Inject constructor(
    @AiHttpClient private val httpClient: HttpClient,
    private val keyStore: GeminiApiKeyStore,
) : GeminiClient {

    override suspend fun generateStructured(
        prompt: String,
        schema: JsonObject,
        model: String,
    ): String {
        val apiKey = keyStore.get()
            ?: throw GeminiException(
                "Gemini API key is not configured. Set it in Settings → AI.",
                reason = GeminiException.Reason.MissingApiKey,
            )

        val request = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = schema,
                temperature = 0.2,
            ),
        )

        val response: GeminiResponse = try {
            httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            ) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        } catch (e: Exception) {
            throw GeminiException(
                "Network error calling Gemini: ${e.message}",
                reason = GeminiException.Reason.Network,
                cause = e,
            )
        }

        return response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: throw GeminiException(
                "Gemini returned no candidates",
                reason = GeminiException.Reason.EmptyResponse,
            )
    }

    override suspend fun ping(model: String) {
        val schema = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("reply", buildJsonObject { put("type", "STRING") })
            })
            put("required", buildJsonArray { add("reply") })
        }
        generateStructured(
            prompt = "Ответь строго JSON-объектом {\"reply\":\"ok\"}.",
            schema = schema,
            model = model,
        )
    }
}

/**
 * Parses Gemini's structured-output JSON into [ParsedTransaction].
 * Tolerant: missing fields become null rather than failures.
 */
internal fun parseTransactionJson(raw: String, json: Json): ParsedTransaction {
    val obj = json.parseToJsonElement(raw).jsonObject
    fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    fun dbl(key: String): Double? = obj[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

    val type = str("transactionType")?.let {
        runCatching { TransactionTypeGuess.valueOf(it.uppercase()) }.getOrNull()
    }

    return ParsedTransaction(
        amount = dbl("amount"),
        currency = str("currency"),
        date = str("date"),
        category = str("category"),
        account = str("account"),
        description = str("description"),
        transactionType = type,
    )
}

/**
 * Builds the JSON Schema for [ParsedTransaction] with category/account enumerations
 * constrained to the lists the caller provides. This is what keeps Gemini from
 * inventing nonexistent categories.
 */
internal fun buildParseSchema(
    categoryNames: List<String>,
    accountNames: List<String>,
): JsonObject = buildJsonObject {
    put("type", "OBJECT")
    put("properties", buildJsonObject {
        put("amount", buildJsonObject { put("type", "NUMBER") })
        put("currency", buildJsonObject { put("type", "STRING") })
        put("date", buildJsonObject {
            put("type", "STRING")
            put("description", "ISO-8601 local date, e.g. 2026-05-23")
        })
        put("category", buildJsonObject {
            put("type", "STRING")
            put("nullable", true)
            if (categoryNames.isNotEmpty()) {
                put("enum", buildJsonArray { categoryNames.forEach { add(it) } })
            }
        })
        put("account", buildJsonObject {
            put("type", "STRING")
            put("nullable", true)
            if (accountNames.isNotEmpty()) {
                put("enum", buildJsonArray { accountNames.forEach { add(it) } })
            }
        })
        put("description", buildJsonObject { put("type", "STRING") })
        put("transactionType", buildJsonObject {
            put("type", "STRING")
            put("enum", buildJsonArray {
                add("EXPENSE"); add("INCOME"); add("TRANSFER")
            })
        })
    })
    put("required", buildJsonArray { add("amount"); add("transactionType") })
}
