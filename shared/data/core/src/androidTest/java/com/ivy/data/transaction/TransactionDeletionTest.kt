package com.ivy.data.transaction

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivy.base.model.TransactionType
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
import com.ivy.data.db.entity.TransactionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransactionDeletionTest {
    private lateinit var db: IvyRoomDatabase

    private data class Graph(
        val accounts: List<AccountEntity>,
        val rules: List<PlannedPaymentRuleEntity>,
        val transactions: List<TransactionEntity>,
        val tags: List<TagEntity>,
        val associations: List<TagAssociationEntity>,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun deletingOneTransactionRemovesAllItsTagLinksAndPreservesOtherRows() = runBlocking {
        val before = seed()
        db.writeTransactionDao.deleteById(id(1))
        assertEquals(without(before, setOf(id(1))), graph())
    }

    @Test
    fun deletingAccountTransactionsPreservesIncomingTransfersFromOtherAccounts() = runBlocking {
        val before = seed()
        db.writeTransactionDao.deleteAllByAccountId(id(90))
        assertEquals(without(before, before.transactions.filter { it.accountId == id(90) }.map { it.id }.toSet()), graph())
    }

    @Test
    fun deletingPendingRuleInstancesPreservesPaidHistoryAndOtherRules() = runBlocking {
        val before = seed()
        db.writeTransactionDao.deletedByRecurringRuleIdAndNoDateTime(id(70))
        assertEquals(without(before, setOf(id(2), id(3))), graph())
    }

    @Test
    fun deletingAllTransactionsPreservesTagDefinitionsAndUnrelatedAssociations() = runBlocking {
        val before = seed()
        db.writeTransactionDao.deleteAll()
        assertEquals(without(before, before.transactions.map { it.id }.toSet()), graph())
    }

    @Test
    fun repeatedDeletionAndMissingIdsPreserveUnrelatedAssociations() = runBlocking {
        val before = seed()
        repeat(2) { db.writeTransactionDao.deleteById(id(1)) }
        db.writeTransactionDao.deleteById(id(999))
        assertEquals(without(before, setOf(id(1))), graph())
    }

    @Test
    fun tombstonedTransactionAndItsAssociationsArePhysicallyRemoved() = runBlocking {
        val before = seed()
        db.writeTransactionDao.deleteById(id(3))
        assertEquals(without(before, setOf(id(3))), graph())
    }

    @Test
    fun singleDeleteFailureRollsBackEarlierAssociationDeletes() = rollbackAfterTags {
        db.writeTransactionDao.deleteById(id(1))
    }

    @Test
    fun accountDeleteFailureRollsBackEarlierAssociationDeletes() = rollbackAfterTags {
        db.writeTransactionDao.deleteAllByAccountId(id(90))
    }

    @Test
    fun pendingRuleDeleteFailureRollsBackEarlierAssociationDeletes() = rollbackAfterTags(target = 2) {
        db.writeTransactionDao.deletedByRecurringRuleIdAndNoDateTime(id(70))
    }

    @Test
    fun deleteAllFailureRollsBackEarlierAssociationDeletes() = rollbackAfterTags {
        db.writeTransactionDao.deleteAll()
    }

    @Test
    fun callerCancellationRollsBackTheJoinedTransaction() = runBlocking {
        val before = seed()
        try {
            db.withTransaction {
                db.writeTransactionDao.deleteById(id(1))
                assertEquals(without(before, setOf(id(1))), graph())
                throw CancellationException("injected caller cancellation")
            }
        } catch (cancelled: CancellationException) {
            assertEquals("injected caller cancellation", cancelled.message)
        }
        assertEquals(before, graph())
    }

    @Test
    fun pendingRuleDeletionExceedsOldAndroidBindingLimitWithoutDeletingPaidHistory() = runBlocking {
        seed()
        val extra = (1_000..2_100).map { number ->
            transaction(number).copy(dateTime = null, recurringRuleId = id(70), dueDate = Instant.EPOCH)
        }
        db.writeTransactionDao.saveMany(extra)
        db.writeTagAssociationDao.save(extra.map { link(it) })
        val before = graph()
        val selected = extra.map { it.id }.toSet() + setOf(id(2), id(3))
        db.writeTransactionDao.deletedByRecurringRuleIdAndNoDateTime(id(70))
        assertEquals(without(before, selected), graph())
    }

    private fun rollbackAfterTags(target: Int = 1, operation: suspend () -> Unit) = runBlocking {
        val before = seed()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER injected_abort BEFORE DELETE ON transactions " +
                "WHEN OLD.id = '${id(target)}' BEGIN SELECT CASE WHEN " +
                "NOT EXISTS(SELECT 1 FROM tags_association WHERE associatedId = OLD.id) " +
                "THEN RAISE(ABORT, 'verified earlier tag deletes') " +
                "ELSE RAISE(ABORT, 'tag deletes missing') END; END;"
        )
        try {
            operation()
            fail("Expected the financial-row delete to abort")
        } catch (error: SQLiteException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("verified earlier tag deletes"))
        }
        assertEquals(before, graph())
    }

    private suspend fun seed(): Graph {
        val accounts = listOf(
            AccountEntity(name = "Target", currency = "USD", color = 0, id = id(90)),
            AccountEntity(name = "Other", currency = "USD", color = 0, id = id(91)),
        )
        val rule = PlannedPaymentRuleEntity(
            startDate = Instant.EPOCH, intervalN = null, intervalType = null, oneTime = true,
            type = TransactionType.EXPENSE, accountId = id(90), amount = 10.0, id = id(70),
        )
        val transactions = listOf(
            transaction(1),
            transaction(2).copy(dateTime = null, dueDate = Instant.EPOCH, recurringRuleId = id(70)),
            transaction(3).copy(dateTime = null, recurringRuleId = id(70), isDeleted = true),
            transaction(4).copy(recurringRuleId = id(70), paidForDateTime = Instant.EPOCH),
            transaction(5).copy(accountId = id(91), dateTime = null, recurringRuleId = id(71)),
            transaction(6).copy(type = TransactionType.TRANSFER, toAccountId = id(91), toAmount = 6.0),
            transaction(7).copy(
                accountId = id(91), type = TransactionType.TRANSFER, toAccountId = id(90), toAmount = 7.0,
            ),
        )
        val tag = TagEntity(
            id = id(80), name = "Shared", description = null, color = 0, icon = null,
            orderNum = 0.0, dateTime = Instant.EPOCH, isDeleted = false, lastSyncedTime = Instant.EPOCH,
        )
        val associations = transactions.map { link(it) } + listOf(
            link(transactions.first()).copy(tagId = id(81), isDeleted = true),
            link(transactions.last()).copy(tagId = id(81)),
            // A non-selected association must not be swept up by deleteAll or a missing-ID retry.
            link(transactions.first()).copy(associatedId = id(999)),
        )
        accounts.forEach { db.writeAccountDao.save(it) }
        db.writePlannedPaymentRuleDao.saveMany(listOf(rule, rule.copy(id = id(71), accountId = id(91))))
        db.writeTransactionDao.saveMany(transactions)
        db.writeTagDao.save(listOf(tag, tag.copy(id = id(81), name = "Second")))
        db.writeTagAssociationDao.save(associations)
        val before = graph()
        assertEquals(transactions, before.transactions)
        assertEquals(associations.toSet(), before.associations.toSet())
        return before
    }

    private suspend fun graph() = Graph(
        (db.accountDao.findAll() + db.accountDao.findAll(deleted = true)).sortedBy { it.id },
        db.plannedPaymentRuleDao.findAllForRestore().sortedBy { it.id },
        db.transactionDao.findAllForRestore().sortedBy { it.id },
        db.tagDao.findAll().sortedBy { it.id },
        db.tagAssociationDao.findAll().sortedWith(compareBy({ it.tagId }, { it.associatedId })),
    )

    private fun without(before: Graph, ids: Set<UUID>) = before.copy(
        transactions = before.transactions.filter { it.id !in ids },
        associations = before.associations.filter { it.associatedId !in ids },
    )

    private fun transaction(number: Int) = TransactionEntity(
        accountId = id(90), type = TransactionType.EXPENSE, amount = number.toDouble(),
        dateTime = Instant.EPOCH, id = id(number),
    )

    private fun link(transaction: TransactionEntity) = TagAssociationEntity(
        tagId = id(80), associatedId = transaction.id,
        lastSyncedTime = Instant.EPOCH, isDeleted = transaction.isDeleted,
    )

    private fun id(number: Int) = UUID(0, number.toLong())
}
