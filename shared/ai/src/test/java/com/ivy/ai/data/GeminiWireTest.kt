package com.ivy.ai.data

import com.ivy.base.di.KotlinxSerializationModule
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiWireTest {
    private val codec = geminiWireJson(KotlinxSerializationModule.provideJson())

    @Test
    fun requestPreservesItsExactJsonShape() {
        val schema = codec.parseToJsonElement("""{"type":"OBJECT"}""")
        val request = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part("hello")))),
            generationConfig = GenerationConfig("application/json", schema, 0.2)
        )
        val expected = codec.parseToJsonElement(
            """
            {
              "contents": [{"parts": [{"text": "hello"}]}],
              "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": {"type": "OBJECT"},
                "temperature": 0.2
              }
            }
            """.trimIndent()
        )
        assertEquals(expected, codec.encodeToJsonElement(GeminiRequest.serializer(), request))
    }

    @Test
    fun responseIgnoresUnusedRoleAndMetadata() {
        assertEquals(
            "draft",
            text(
                """
                {
                  "candidates": [{"content": {"parts": [{"text": "draft"}], "role": "model"}}],
                  "usageMetadata": {"totalTokenCount": 10}
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun absentOrEmptyCandidatesHaveNoText() {
        val responses = listOf(
            "{}",
            """{"promptFeedback":{"blockReason":"OTHER"}}""",
            """{"candidates":[]}"""
        )
        for (raw in responses) {
            assertNull(text(raw))
        }
    }

    @Test
    fun missingCandidateContentHasNoText() {
        assertNull(text("""{"candidates":[{}]}"""))
    }

    @Test
    fun explicitlyNullOptionalResponseFieldsHaveNoText() {
        assertNull(text("""{"candidates":null}"""))
        assertNull(text("""{"candidates":[{"content":null}]}"""))
    }

    @Test(expected = SerializationException::class)
    fun missingRequiredPartsRemainsAnInvalidResponse() {
        text("""{"candidates":[{"content":{}}]}""")
    }

    @Test
    fun onlyTheFirstCandidateIsUsed() {
        assertNull(text("""{"candidates":[{}, {"content":{"parts":[{"text":"later"}]}}]}"""))
    }

    private fun text(raw: String): String? =
        codec.decodeFromString(GeminiResponse.serializer(), raw).textOrNull()
}
