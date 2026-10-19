package com.ivy.data.backup

import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupMergePlannerTest {
    private val account = AccountEntity(name = "Cash", currency = "USD", color = 0)
    private val transaction = TransactionEntity(
        accountId = account.id,
        type = TransactionType.EXPENSE,
        amount = 12.0,
        dateTime = Instant.EPOCH,
        description = "Keep this UUID: ${account.id}",
    )
    private val data = IvyWalletCompleteData(
        formatVersion = 2,
        accounts = listOf(account),
        transactions = listOf(transaction),
    )

    @Test
    fun `same UUID with different content fails before any merge`() {
        val conflict = data.copy(accounts = listOf(account.copy(name = "Changed")))
        try {
            BackupMergePlanner.plan(conflict, data)
            throw AssertionError("Expected a UUID conflict")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Conflicting account ID"))
        }
    }

    @Test
    fun `repeat import skips identical identities`() {
        val plan = BackupMergePlanner.plan(data, data)
        assertTrue(plan.additions.accounts.isEmpty())
        assertTrue(plan.additions.transactions.isEmpty())
        assertEquals(1, plan.skippedTransactions)
    }

    @Test
    fun `same name accounts with different currencies remain distinct`() {
        val other = AccountEntity(name = account.name, currency = "EUR", color = 0)
        val plan = BackupMergePlanner.plan(
            data.copy(accounts = listOf(other), transactions = emptyList()),
            data
        )
        assertEquals(listOf(other), plan.additions.accounts)
    }

    @Test
    fun `UUID in note text is never rewritten`() {
        val plan = BackupMergePlanner.plan(data, IvyWalletCompleteData(formatVersion = 2))
        assertEquals(transaction.description, plan.additions.transactions.single().description)
        assertEquals(account.id, plan.additions.transactions.single().accountId)
    }

    @Test
    fun `deleted account identity cannot be resurrected`() {
        expectConflict("Conflicting account ID", data, data.copy(
            accounts = listOf(account.copy(isDeleted = true))
        ))
    }

    @Test
    fun `deleted category identity cannot be resurrected`() {
        val category = com.ivy.data.db.entity.CategoryEntity(name = "Food", color = 0)
        expectConflict("Conflicting category ID", data.copy(categories = listOf(category)),
            data.copy(categories = listOf(category.copy(isDeleted = true))))
    }

    @Test
    fun `deleted transaction identity cannot be resurrected`() {
        expectConflict("Conflicting transaction ID", data, data.copy(
            transactions = listOf(transaction.copy(isDeleted = true))
        ))
    }

    private val rule = com.ivy.data.db.entity.PlannedPaymentRuleEntity(
        startDate = Instant.EPOCH, intervalN = null, intervalType = null,
        oneTime = true, type = TransactionType.EXPENSE, accountId = account.id, amount = 5.0,
    )

    @Test
    fun `incoming archived account with incoming live rule fails`() {
        expectConflict("archived account", data.copy(
            accounts = listOf(account.copy(archived = true)), plannedPaymentRules = listOf(rule)
        ), IvyWalletCompleteData(formatVersion = 2))
    }

    @Test
    fun `incoming live rule against existing archived account fails`() {
        expectConflict("archived account", IvyWalletCompleteData(
            formatVersion = 2, plannedPaymentRules = listOf(rule)
        ), data.copy(accounts = listOf(account.copy(archived = true))))
    }

    @Test
    fun `archived account without live rules is accepted`() {
        val archived = data.copy(accounts = listOf(account.copy(archived = true)),
            plannedPaymentRules = listOf(rule.copy(isDeleted = true)))
        val plan = BackupMergePlanner.plan(archived, IvyWalletCompleteData(formatVersion = 2))
        assertTrue(plan.additions.accounts.single().archived)
        assertTrue(plan.additions.plannedPaymentRules.isEmpty())
    }

    private fun expectConflict(message: String, incoming: IvyWalletCompleteData, current: IvyWalletCompleteData) {
        try {
            BackupMergePlanner.plan(incoming, current)
            throw AssertionError("Expected conflict: $message")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message))
        }
    }
}
