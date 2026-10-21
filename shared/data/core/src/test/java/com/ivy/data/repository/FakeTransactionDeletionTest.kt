package com.ivy.data.repository

import com.ivy.base.model.TransactionType
import com.ivy.data.db.dao.fake.FakeTagAssociationDao
import com.ivy.data.db.dao.fake.FakeTransactionDao
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TransactionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class FakeTransactionDeletionTest {
    @Test
    fun pendingDeletionPreservesPaidHistoryAndItsTagsLikeTheRoomDao() = runTest {
        val links = FakeTagAssociationDao()
        val transactions = FakeTransactionDao(tagAssociations = links)
        val pending = TransactionEntity(
            accountId = id(90), type = TransactionType.EXPENSE, amount = 10.0,
            recurringRuleId = id(70), dateTime = null, id = id(1),
        )
        val paid = pending.copy(id = id(2), dateTime = Instant.EPOCH)
        val deletedPending = pending.copy(id = id(3), isDeleted = true)
        val otherRule = pending.copy(id = id(4), recurringRuleId = id(71))
        val rows = listOf(pending, paid, deletedPending, otherRule)
        transactions.saveMany(rows)
        links.save(rows.map {
            TagAssociationEntity(id(80), it.id, Instant.EPOCH, it.isDeleted)
        })

        transactions.deletedByRecurringRuleIdAndNoDateTime(id(70))

        assertEquals(listOf(paid, otherRule), transactions.findAllForRestore())
        assertEquals(listOf(paid.id, otherRule.id), links.findAll().map { it.associatedId })
    }

    private fun id(number: Int) = UUID(0, number.toLong())
}
