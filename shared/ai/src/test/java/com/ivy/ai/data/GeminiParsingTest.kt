package com.ivy.ai.data

import com.ivy.ai.domain.TransactionTypeGuess
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class GeminiParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    // region parseTransactionsJson
    @Test
    fun `parses an array with a full transaction`() {
        val raw = """
            [{
              "amount": 1500.0,
              "currency": "RUB",
              "date": "2026-05-23",
              "category": "Продукты",
              "account": "Наличные",
              "description": "Пятёрочка",
              "transactionType": "EXPENSE"
            }]
        """.trimIndent()

        val result = parseTransactionsJson(raw, json)

        result shouldHaveSize 1
        with(result.first()) {
            amount shouldBe 1500.0
            currency shouldBe "RUB"
            date shouldBe "2026-05-23"
            category shouldBe "Продукты"
            account shouldBe "Наличные"
            description shouldBe "Пятёрочка"
            transactionType shouldBe TransactionTypeGuess.EXPENSE
        }
    }

    @Test
    fun `parses multiple transactions`() {
        val raw = """
            [
              { "amount": 1500, "transactionType": "EXPENSE", "description": "продукты" },
              { "amount": 80000, "transactionType": "INCOME", "description": "зарплата" },
              { "amount": 250, "transactionType": "EXPENSE", "description": "кофе" }
            ]
        """.trimIndent()

        val result = parseTransactionsJson(raw, json)

        result shouldHaveSize 3
        result.map { it.amount } shouldBe listOf(1500.0, 80000.0, 250.0)
        result[1].transactionType shouldBe TransactionTypeGuess.INCOME
    }

    @Test
    fun `tolerates a single bare object`() {
        val raw = """{ "amount": 10, "transactionType": "EXPENSE" }"""

        val result = parseTransactionsJson(raw, json)

        result shouldHaveSize 1
        result.first().amount shouldBe 10.0
    }

    @Test
    fun `missing optional fields become null`() {
        val result = parseTransactionsJson(
            """[{ "amount": 80000, "transactionType": "INCOME" }]""",
            json,
        ).first()

        result.currency.shouldBeNull()
        result.date.shouldBeNull()
        result.category.shouldBeNull()
        result.account.shouldBeNull()
        result.description.shouldBeNull()
    }

    @Test
    fun `blank strings are treated as null`() {
        val result = parseTransactionsJson(
            """[{ "amount": 10, "category": "  ", "account": "", "description": " " }]""",
            json,
        ).first()

        result.category.shouldBeNull()
        result.account.shouldBeNull()
        result.description.shouldBeNull()
    }

    @Test
    fun `transactionType is case-insensitive and tolerant of unknown values`() {
        parseTransactionsJson("""[{ "transactionType": "transfer" }]""", json).first()
            .transactionType shouldBe TransactionTypeGuess.TRANSFER

        parseTransactionsJson("""[{ "transactionType": "WUT" }]""", json).first()
            .transactionType.shouldBeNull()
    }

    @Test
    fun `non-numeric amount becomes null`() {
        parseTransactionsJson("""[{ "amount": "не число" }]""", json).first()
            .amount.shouldBeNull()
    }
    // endregion

    // region buildParseSchema
    @Test
    fun `schema is an array of transaction objects`() {
        val schema = buildParseSchema(categoryNames = emptyList(), accountNames = emptyList())

        schema["type"].toString().trim('"') shouldBe "ARRAY"
        schema["items"]!!.jsonObject["type"].toString().trim('"') shouldBe "OBJECT"
    }

    @Test
    fun `schema constrains category and account to provided names`() {
        val schema = buildParseSchema(
            categoryNames = listOf("Продукты", "Транспорт"),
            accountNames = listOf("Наличные"),
        )

        val properties = schema["items"]!!.jsonObject["properties"]!!.jsonObject
        val categoryEnum = properties["category"]!!.jsonObject["enum"] as JsonArray
        val accountEnum = properties["account"]!!.jsonObject["enum"] as JsonArray

        categoryEnum.map { it.toString().trim('"') } shouldBe listOf("Продукты", "Транспорт")
        accountEnum.map { it.toString().trim('"') } shouldBe listOf("Наличные")
    }

    @Test
    fun `schema omits enum when no names are available`() {
        val properties = buildParseSchema(emptyList(), emptyList())["items"]!!
            .jsonObject["properties"]!!.jsonObject
        properties["category"]!!.jsonObject["enum"].shouldBeNull()
        properties["account"]!!.jsonObject["enum"].shouldBeNull()
    }

    @Test
    fun `each item requires amount and transactionType`() {
        val required = buildParseSchema(listOf("X"), listOf("Y"))["items"]!!
            .jsonObject["required"] as JsonArray
        required.map { it.toString().trim('"') } shouldBe listOf("amount", "transactionType")
    }

    @Test
    fun `transactionType enum lists all guesses`() {
        val typeEnum = buildParseSchema(emptyList(), emptyList())["items"]!!
            .jsonObject["properties"]!!.jsonObject["transactionType"]!!
            .jsonObject["enum"] as JsonArray
        typeEnum.map { it.toString().trim('"') } shouldBe listOf("EXPENSE", "INCOME", "TRANSFER")
    }
    // endregion

    @Test
    fun `GeminiException defaults to Unknown reason`() {
        GeminiException("boom").reason shouldBe GeminiException.Reason.Unknown
    }
}
