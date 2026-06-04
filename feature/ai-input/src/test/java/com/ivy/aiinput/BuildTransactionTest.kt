package com.ivy.aiinput

import com.ivy.base.model.TransactionType
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BuildTransactionTest {

    private val cashId = UUID.randomUUID()
    private val cardId = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()
    private val fixedNow = Instant.parse("2026-06-04T10:00:00Z")

    private val accounts = listOf(
        account("Наличные", cashId, "RUB"),
        account("Карта", cardId, "USD"),
    )

    @Test
    fun `builds an expense on the resolved account with its currency`() {
        val tx = draft(type = TransactionType.EXPENSE, accountId = cardId, amount = 250.0)
            .toDomainTransaction(accounts, now = fixedNow)

        val expense = tx.shouldBeInstanceOf<Expense>()
        expense.account shouldBe AccountId(cardId)
        expense.value.amount.value shouldBe 250.0
        expense.value.asset shouldBe AssetCode.unsafe("USD")
    }

    @Test
    fun `builds an income`() {
        val tx = draft(type = TransactionType.INCOME, accountId = cashId, amount = 80000.0)
            .toDomainTransaction(accounts, now = fixedNow)

        tx.shouldBeInstanceOf<Income>()
    }

    @Test
    fun `transfer falls back to an expense`() {
        val tx = draft(type = TransactionType.TRANSFER, accountId = cashId, amount = 500.0)
            .toDomainTransaction(accounts, now = fixedNow)

        tx.shouldBeInstanceOf<Expense>()
    }

    @Test
    fun `falls back to the first account when none is resolved`() {
        val tx = draft(type = TransactionType.EXPENSE, accountId = null, amount = 10.0)
            .toDomainTransaction(accounts, now = fixedNow)

        tx.shouldBeInstanceOf<Expense>().account shouldBe AccountId(cashId)
    }

    @Test
    fun `uses the draft datetime, otherwise now`() {
        val withDate = draft(amount = 10.0, dateTime = Instant.parse("2026-05-23T00:00:00Z"))
            .toDomainTransaction(accounts, now = fixedNow)!!
        withDate.time shouldBe Instant.parse("2026-05-23T00:00:00Z")

        val withoutDate = draft(amount = 10.0, dateTime = null)
            .toDomainTransaction(accounts, now = fixedNow)!!
        withoutDate.time shouldBe fixedNow
    }

    @Test
    fun `returns null without a positive amount`() {
        draft(amount = null).toDomainTransaction(accounts, now = fixedNow).shouldBeNull()
        draft(amount = 0.0).toDomainTransaction(accounts, now = fixedNow).shouldBeNull()
    }

    @Test
    fun `returns null when there are no accounts`() {
        draft(amount = 100.0).toDomainTransaction(emptyList(), now = fixedNow).shouldBeNull()
    }

    private fun draft(
        type: TransactionType = TransactionType.EXPENSE,
        accountId: UUID? = cashId,
        categoryId: UUID? = this.categoryId,
        amount: Double? = 100.0,
        title: String? = "title",
        description: String? = "desc",
        dateTime: Instant? = null,
    ) = EditDraft(
        type = type,
        accountId = accountId,
        categoryId = categoryId,
        amount = amount,
        title = title,
        description = description,
        dateTime = dateTime,
    )

    private fun account(name: String, id: UUID, currency: String) = Account(
        id = AccountId(id),
        name = NotBlankTrimmedString.unsafe(name),
        asset = AssetCode.unsafe(currency),
        color = ColorInt(0),
        icon = null,
        includeInBalance = true,
        orderNum = 0.0,
    )
}
