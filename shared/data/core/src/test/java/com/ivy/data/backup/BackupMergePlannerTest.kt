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
            data.copy(accounts = listOf(other), transactions = emptyList()), data
        )
        assertEquals(listOf(other), plan.additions.accounts)
    }

    @Test
    fun `UUID in note text is never rewritten`() {
        val plan = BackupMergePlanner.plan(data, IvyWalletCompleteData(formatVersion = 2))
        assertEquals(transaction.description, plan.additions.transactions.single().description)
        assertEquals(account.id, plan.additions.transactions.single().accountId)
    }
}
