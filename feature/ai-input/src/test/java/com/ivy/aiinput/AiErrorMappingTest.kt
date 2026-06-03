package com.ivy.aiinput

import com.ivy.ai.data.GeminiException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.io.IOException

class AiErrorMappingTest {

    @Test
    fun `missing api key maps to MissingApiKey`() {
        GeminiException("no key", GeminiException.Reason.MissingApiKey)
            .toAiError() shouldBe AiError.MissingApiKey
    }

    @Test
    fun `network failure maps to Network`() {
        GeminiException("offline", GeminiException.Reason.Network)
            .toAiError() shouldBe AiError.Network
    }

    @Test
    fun `empty response maps to a Failed with a hint`() {
        GeminiException("no candidates", GeminiException.Reason.EmptyResponse)
            .toAiError().shouldBeInstanceOf<AiError.Failed>()
    }

    @Test
    fun `unknown gemini failure carries its message`() {
        val error = GeminiException("HTTP 500", GeminiException.Reason.Unknown).toAiError()
        error.shouldBeInstanceOf<AiError.Failed>()
        error.message shouldBe "HTTP 500"
    }

    @Test
    fun `non-gemini throwable maps to Failed`() {
        IOException("disk full").toAiError().shouldBeInstanceOf<AiError.Failed>()
    }
}
