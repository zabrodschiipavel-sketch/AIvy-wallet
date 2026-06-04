package com.ivy.aiinput

import com.ivy.ai.domain.ParsedTransaction
import com.ivy.ai.domain.TransactionTypeGuess
import com.ivy.base.model.TransactionType
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Instant
import java.util.UUID

class EditDraftMapperTest {

    private val cashId = UUID.randomUUID()
    private val cardId = UUID.randomUUID()
    private val groceriesId = UUID.randomUUID()

    private val accounts = listOf(
        account("Наличные", cashId),
        account("Карта", cardId),
    )
    private val categories = listOf(
        category("Продукты", groceriesId),
    )

    @Test
    fun `maps expense by default`() {
        parsed(type = null).toEditDraft(accounts, categories).type shouldBe TransactionType.EXPENSE
        parsed(type = TransactionTypeGuess.EXPENSE).toEditDraft(accounts, categories)
            .type shouldBe TransactionType.EXPENSE
    }

    @Test
    fun `maps income and transfer`() {
        parsed(type = TransactionTypeGuess.INCOME).toEditDraft(accounts, categories)
            .type shouldBe TransactionType.INCOME
        parsed(type = TransactionTypeGuess.TRANSFER).toEditDraft(accounts, categories)
            .type shouldBe TransactionType.TRANSFER
    }

    @Test
    fun `resolves account by name case-insensitively`() {
        parsed(account = "наличные").toEditDraft(accounts, categories)
            .accountId shouldBe cashId
    }

    @Test
    fun `unknown or missing account resolves to null`() {
        parsed(account = "Сбербанк").toEditDraft(accounts, categories).accountId.shouldBeNull()
        parsed(account = null).toEditDraft(accounts, categories).accountId.shouldBeNull()
    }

    @Test
    fun `resolves category for non-transfers`() {
        parsed(category = "продукты").toEditDraft(accounts, categories)
            .categoryId shouldBe groceriesId
    }

    @Test
    fun `transfers carry no category even if the model picked one`() {
        parsed(type = TransactionTypeGuess.TRANSFER, category = "Продукты")
            .toEditDraft(accounts, categories)
            .categoryId.shouldBeNull()
    }

    @Test
    fun `keeps only a positive amount`() {
        parsed(amount = 1500.0).toEditDraft(accounts, categories).amount shouldBe 1500.0
        parsed(amount = 0.0).toEditDraft(accounts, categories).amount.shouldBeNull()
        parsed(amount = -10.0).toEditDraft(accounts, categories).amount.shouldBeNull()
        parsed(amount = null).toEditDraft(accounts, categories).amount.shouldBeNull()
    }

    @Test
    fun `fills both title and description from the parsed description`() {
        val draft = parsed(description = "Пятёрочка").toEditDraft(accounts, categories)
        draft.title shouldBe "Пятёрочка"
        draft.description shouldBe "Пятёрочка"
    }

    @Test
    fun `parses an ISO date into start-of-day UTC`() {
        parsed(date = "2026-05-23").toEditDraft(accounts, categories)
            .dateTime shouldBe Instant.parse("2026-05-23T00:00:00Z")
    }

    @Test
    fun `ignores an unparseable or missing date`() {
        parsed(date = "вчера").toEditDraft(accounts, categories).dateTime.shouldBeNull()
        parsed(date = null).toEditDraft(accounts, categories).dateTime.shouldBeNull()
    }

    private fun parsed(
        amount: Double? = 100.0,
        currency: String? = null,
        date: String? = null,
        category: String? = null,
        account: String? = null,
        description: String? = null,
        type: TransactionTypeGuess? = TransactionTypeGuess.EXPENSE,
    ) = ParsedTransaction(
        amount = amount,
        currency = currency,
        date = date,
        category = category,
        account = account,
        description = description,
        transactionType = type,
    )

    private fun account(name: String, id: UUID) = Account(
        id = AccountId(id),
        name = NotBlankTrimmedString.unsafe(name),
        asset = AssetCode.unsafe("RUB"),
        color = ColorInt(0),
        icon = null,
        includeInBalance = true,
        orderNum = 0.0,
    )

    private fun category(name: String, id: UUID) = Category(
        id = CategoryId(id),
        name = NotBlankTrimmedString.unsafe(name),
        color = ColorInt(0),
        icon = null,
        orderNum = 0.0,
    )
}
