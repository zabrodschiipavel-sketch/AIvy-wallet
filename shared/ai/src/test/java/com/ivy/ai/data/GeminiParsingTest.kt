package com.ivy.ai.data

import com.ivy.ai.domain.TransactionTypeGuess
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class GeminiParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    // region parseTransactionJson
    @Test
    fun `parses a full transaction`() {
        val raw = """
            {
              "amount": 1500.0,
              "currency": "RUB",
              "date": "2026-05-23",
              "category": "Продукты",
              "account": "Наличные",
              "description": "Пятёрочка",
              "transactionType": "EXPENSE"
            }
        """.trimIndent()

        val result = parseTransactionJson(raw, json)

        result.amount shouldBe 1500.0
        result.currency shouldBe "RUB"
        result.date shouldBe "2026-05-23"
        result.category shouldBe "Продукты"
        result.account shouldBe "Наличные"
        result.description shouldBe "Пятёрочка"
        result.transactionType shouldBe TransactionTypeGuess.EXPENSE
    }

    @Test
    fun `missing optional fields become null`() {
        val raw = """{ "amount": 80000, "transactionType": "INCOME" }"""

        val result = parseTransactionJson(raw, json)

        result.amount shouldBe 80000.0
        result.transactionType shouldBe TransactionTypeGuess.INCOME
        result.currency.shouldBeNull()
        result.date.shouldBeNull()
        result.category.shouldBeNull()
        result.account.shouldBeNull()
        result.description.shouldBeNull()
    }

    @Test
    fun `blank strings are treated as null`() {
        val raw = """
            { "amount": 10, "category": "  ", "account": "", "description": " " }
        """.trimIndent()

        val result = parseTransactionJson(raw, json)

        result.category.shouldBeNull()
        result.account.shouldBeNull()
        result.description.shouldBeNull()
    }

    @Test
    fun `transactionType is case-insensitive and tolerant of unknown values`() {
        parseTransactionJson("""{ "transactionType": "transfer" }""", json)
            .transactionType shouldBe TransactionTypeGuess.TRANSFER

        parseTransactionJson("""{ "transactionType": "WUT" }""", json)
            .transactionType.shouldBeNull()
    }

    @Test
    fun `non-numeric amount becomes null`() {
        parseTransactionJson("""{ "amount": "не число" }""", json)
            .amount.shouldBeNull()
    }
    // endregion

    // region buildParseSchema
    @Test
    fun `schema constrains category and account to provided names`() {
        val schema = buildParseSchema(
            categoryNames = listOf("Продукты", "Транспорт"),
            accountNames = listOf("Наличные"),
        )

        val properties = schema["properties"]!!.jsonObject
        val categoryEnum = properties["category"]!!.jsonObject["enum"] as JsonArray
        val accountEnum = properties["account"]!!.jsonObject["enum"] as JsonArray

        categoryEnum.map { it.toString().trim('"') } shouldBe listOf("Продукты", "Транспорт")
        accountEnum.map { it.toString().trim('"') } shouldBe listOf("Наличные")
    }

    @Test
    fun `schema omits enum when no names are available`() {
        val schema = buildParseSchema(categoryNames = emptyList(), accountNames = emptyList())

        val properties = schema["properties"]!!.jsonObject
        properties["category"]!!.jsonObject["enum"].shouldBeNull()
        properties["account"]!!.jsonObject["enum"].shouldBeNull()
    }

    @Test
    fun `schema requires amount and transactionType`() {
        val schema = buildParseSchema(categoryNames = listOf("X"), accountNames = listOf("Y"))

        val required = (schema["required"] as JsonArray).map { it.toString().trim('"') }
        required shouldBe listOf("amount", "transactionType")
    }

    @Test
    fun `transactionType enum lists all guesses`() {
        val schema: JsonObject = buildParseSchema(emptyList(), emptyList())

        val typeEnum = schema["properties"]!!.jsonObject["transactionType"]!!
            .jsonObject["enum"] as JsonArray
        typeEnum.map { it.toString().trim('"') } shouldBe listOf("EXPENSE", "INCOME", "TRANSFER")
    }
    // endregion

    @Test
    fun `GeminiException defaults to Unknown reason`() {
        GeminiException("boom").reason shouldBe GeminiException.Reason.Unknown
    }
}
