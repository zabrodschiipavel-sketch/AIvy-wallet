package com.ivy.data.loan

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivy.base.TestDispatchersProvider
import com.ivy.base.model.TransactionType
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
import com.ivy.data.model.LoanType
import com.ivy.data.model.sync.Identifiable
import com.ivy.data.model.sync.UniqueId
import com.ivy.data.repository.RepositoryMemo
import com.ivy.data.repository.RepositoryMemoFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoanDeletionCommandTest {
    private lateinit var db: IvyRoomDatabase
    private lateinit var observer: DataObserver
    private lateinit var command: LoanDeletionCommand
    private lateinit var memo: RepositoryMemo<CachedTransaction, RowId>
    private var loads = 0

    private data class RowId(override val value: UUID) : UniqueId
    private data class CachedTransaction(val row: TransactionEntity) : Identifiable<RowId> {
        override val id: RowId get() = RowId(row.id)
    }
    private data class Graph(
        val accounts: List<AccountEntity>,
        val loans: List<LoanEntity>,
        val records: List<LoanRecordEntity>,
        val transactions: List<TransactionEntity>,
        val tags: List<TagEntity>,
        val associations: List<TagAssociationEntity>,
    )

    @Before
    fun setUp() {
        // Use Room's real query/transaction executors, as in the backup fixtures.
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
        observer = DataObserver()
        val factory = RepositoryMemoFactory(observer, TestDispatchersProvider)
        memo = factory.createMemo(
            getDataWriteSaveEvent = { DataWriteEvent.AllDataChange },
            getDateWriteDeleteEvent = { DataWriteEvent.AllDataChange },
        )
        command = LoanDeletionCommand(db, factory, observer, TestDispatchersProvider)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun deleteLoanRemovesFullGraphIncludingTombstonesAndEitherTransactionLink() = runBlocking {
        val before = seed()
        val expected = afterLoan(before)
        observing { events, committed ->
            command.deleteLoan(id(1))
            assertEquals(expected, committed.receive())
            assertEquals(expected, graph())
            assertEquals(listOf(DataWriteEvent.AllDataChange), events)
            assertReloaded(expected)
        }
    }

    @Test
    fun deleteRecordRemovesEveryLinkedFinancialRowButPreservesLoanAndOtherRecords() = runBlocking {
        val before = seed()
        val expected = afterRecord(before)
        observing { events, committed ->
            command.deleteLoanRecord(id(11))
            assertEquals(expected, committed.receive())
            assertEquals(expected, graph())
            assertEquals(listOf(DataWriteEvent.AllDataChange), events)
            assertReloaded(expected)
        }
    }

    @Test
    fun repeatLoanDeletionIsIdempotentInDatabase() = runBlocking {
        val expected = afterLoan(seed())
        observing { events, committed ->
            repeat(2) {
                command.deleteLoan(id(1))
                assertEquals(expected, committed.receive())
                assertEquals(expected, graph())
            }
            assertEquals(List(2) { DataWriteEvent.AllDataChange }, events)
            assertReloaded(expected)
        }
    }

    @Test
    fun repeatRecordDeletionIsIdempotentInDatabase() = runBlocking {
        val expected = afterRecord(seed())
        observing { events, committed ->
            repeat(2) {
                command.deleteLoanRecord(id(11))
                assertEquals(expected, committed.receive())
                assertEquals(expected, graph())
            }
            assertEquals(List(2) { DataWriteEvent.AllDataChange }, events)
            assertReloaded(expected)
        }
    }

    @Test
    fun loanRecordDeleteAbortRestoresFinancialRowsAlreadyDeletedByLoanCommand() = runBlocking {
        val before = seed()
        abortAfterWrites(
            table = "loan_records",
            target = 11,
            earlierWrites = "NOT EXISTS (SELECT 1 FROM transactions WHERE id IN " +
                "('${id(21)}', '${id(22)}', '${id(23)}', '${id(24)}', '${id(25)}'))",
        )
        assertRollback(before) { command.deleteLoan(id(1)) }
    }

    @Test
    fun loanDeleteAbortRestoresRecordsAndFinancialRowsAlreadyRemoved() = runBlocking {
        val before = seed()
        abortAfterWrites(
            table = "loans",
            target = 1,
            earlierWrites = "NOT EXISTS (SELECT 1 FROM loan_records WHERE loanId = '${id(1)}') " +
                "AND NOT EXISTS (SELECT 1 FROM transactions WHERE id IN " +
                "('${id(21)}', '${id(22)}', '${id(23)}', '${id(24)}', '${id(25)}'))",
        )
        assertRollback(before) { command.deleteLoan(id(1)) }
    }

    @Test
    fun recordDeleteAbortRestoresAllItsFinancialRowsAndOriginalMemoSnapshot() = runBlocking {
        val before = seed()
        abortAfterWrites(
            table = "loan_records",
            target = 11,
            earlierWrites = "NOT EXISTS (SELECT 1 FROM transactions WHERE loanRecordId = '${id(11)}')",
        )
        assertRollback(before) { command.deleteLoanRecord(id(11)) }
    }

    @Test
    fun loanFinancialDeleteFailureRestoresAlreadyRemovedTagAssociations() = runBlocking {
        val before = seed()
        abortAfterWrites(
            table = "transactions",
            target = 21,
            earlierWrites = "NOT EXISTS (SELECT 1 FROM tags_association WHERE associatedId IN " +
                "('${id(21)}', '${id(22)}', '${id(23)}', '${id(24)}', '${id(25)}'))",
        )
        assertRollback(before) { command.deleteLoan(id(1)) }
    }

    @Test
    fun recordFinancialDeleteFailureRestoresAlreadyRemovedTagAssociations() = runBlocking {
        val before = seed()
        abortAfterWrites(
            table = "transactions",
            target = 22,
            earlierWrites = "NOT EXISTS (SELECT 1 FROM tags_association WHERE associatedId IN " +
                "('${id(22)}', '${id(23)}'))",
        )
        assertRollback(before) { command.deleteLoanRecord(id(11)) }
    }

    @Test
    fun deletingMoreThanSqliteBindingLimitPreservesUnrelatedTagsAndTransactions() = runBlocking {
        seed()
        val extra = (1_000..2_000).map { transaction(it, loan = 1) }
        db.writeTransactionDao.saveMany(extra)
        db.writeTagAssociationDao.save(extra.map { tagLink(it) })
        val expected = afterLoan(graph())
        command.deleteLoan(id(1))
        assertEquals(expected, graph())
    }

    private suspend fun assertRollback(before: Graph, operation: suspend () -> Unit) {
        observing { events, committed ->
            try {
                operation()
                fail("Expected the post-write trigger to abort")
            } catch (error: SQLiteException) {
                // The other CASE branch indicates the command never reached the intended seam.
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("verified earlier deletes"))
            }
            yield()
            assertEquals(before, graph())
            assertTrue(events.isEmpty())
            assertTrue(committed.tryReceive().isFailure)
            assertReloaded(before)
        }
    }

    private fun abortAfterWrites(table: String, target: Int, earlierWrites: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER injected_abort BEFORE DELETE ON $table " +
                "WHEN OLD.id = '${id(target)}' BEGIN " +
                "SELECT CASE WHEN $earlierWrites " +
                "THEN RAISE(ABORT, 'verified earlier deletes') " +
                "ELSE RAISE(ABORT, 'earlier deletes missing') END; END;"
        )
    }

    private suspend fun observing(
        block: suspend (List<DataWriteEvent>, Channel<Graph>) -> Unit,
    ) = withTimeout(10_000) {
        coroutineScope {
            val events = mutableListOf<DataWriteEvent>()
            val committed = Channel<Graph>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                observer.writeEvents.collect {
                    events.add(it)
                    // Re-enter Room from a distinct coroutine: an event under the transaction
                    // lock would deadlock here and fail the bounded receive in the caller.
                    committed.send(graph())
                }
            }
            try { block(events, committed) } finally {
                collector.cancelAndJoin()
                committed.close()
            }
        }
    }

    private suspend fun cachedTransactions(): List<TransactionEntity> = memo.findAll(
        findAllOperation = {
            loads++
            db.transactionDao.findAllForRestore().sortedBy { it.id }.map { CachedTransaction(it) }
        },
        sortMemo = { sortedBy { it.id.value } },
    ).map { it.row }

    private suspend fun assertReloaded(expected: Graph) {
        assertFalse(memo.findAllMemoized)
        val previousLoads = loads
        assertEquals(expected.transactions, cachedTransactions())
        assertEquals(previousLoads + 1, loads)
        assertEquals(expected.transactions, cachedTransactions())
        assertEquals(previousLoads + 1, loads)
    }

    private suspend fun graph() = Graph(
        (db.accountDao.findAll() + db.accountDao.findAll(deleted = true)).sortedBy { it.id },
        db.loanDao.findAllForRestore().sortedBy { it.id },
        db.loanRecordDao.findAllForRestore().sortedBy { it.id },
        db.transactionDao.findAllForRestore().sortedBy { it.id },
        db.tagDao.findAll().sortedBy { it.id },
        db.tagAssociationDao.findAll().sortedWith(compareBy({ it.tagId }, { it.associatedId })),
    )

    private fun afterLoan(before: Graph) = Graph(
        before.accounts,
        before.loans.filter { it.id != id(1) },
        before.records.filter { it.id == id(14) },
        before.transactions.filter { it.id in listOf(id(26), id(27)) },
        before.tags,
        before.associations.filter { it.associatedId in listOf(id(26), id(27)) },
    )

    private fun afterRecord(before: Graph) = Graph(
        before.accounts,
        before.loans,
        before.records.filter { it.id != id(11) },
        before.transactions.filter { it.id in listOf(id(21), id(24), id(25), id(26), id(27)) },
        before.tags,
        before.associations.filter { it.associatedId !in listOf(id(22), id(23)) },
    )

    private suspend fun seed(): Graph {
        val account = AccountEntity(name = "Shared account", currency = "USD", color = 0, id = id(90))
        val loans = listOf(
            LoanEntity("Target", 100.0, LoanType.BORROW, accountId = id(90), id = id(1)),
            LoanEntity("Unrelated", 200.0, LoanType.LEND, accountId = id(90), id = id(2)),
        )
        val records = listOf(
            LoanRecordEntity(id(1), 10.0, dateTime = Instant.EPOCH, accountId = id(90), id = id(11)),
            LoanRecordEntity(id(1), 20.0, dateTime = Instant.EPOCH, id = id(12)),
            LoanRecordEntity(id(1), 30.0, dateTime = Instant.EPOCH, isDeleted = true, id = id(13)),
            LoanRecordEntity(id(2), 40.0, dateTime = Instant.EPOCH, id = id(14)),
        )
        val transactions = listOf(
            transaction(21, loan = 1), // loan-only link
            transaction(22, loan = 1, record = 11),
            transaction(23, record = 11), // second financial row, null loanId
            transaction(24, record = 12),
            transaction(25, record = 13, deleted = true), // tombstoned record and transaction
            transaction(26, loan = 2, record = 14),
            transaction(27), // same account, unrelated to any loan
        )
        val tag = TagEntity(
            id = id(80),
            name = "Shared tag",
            description = null,
            color = 0,
            icon = null,
            orderNum = 0.0,
            dateTime = Instant.EPOCH,
            isDeleted = false,
            lastSyncedTime = Instant.EPOCH,
        )
        val tags = listOf(tag, tag.copy(id = id(81), name = "Second tag"))
        val associations = transactions.map { tagLink(it) } + listOf(
            tagLink(transactions[1]).copy(tagId = id(81)),
            tagLink(transactions.last()).copy(tagId = id(81)),
        )
        db.writeAccountDao.save(account)
        db.writeLoanDao.saveMany(loans)
        db.writeLoanRecordDao.saveMany(records)
        db.writeTransactionDao.saveMany(transactions)
        db.writeTagDao.save(tags)
        db.writeTagAssociationDao.save(associations)
        val before = graph()
        assertEquals(
            Graph(listOf(account), loans, records, transactions, tags, associations),
            before,
        )
        assertEquals(before.transactions, cachedTransactions())
        assertEquals(before.transactions, cachedTransactions())
        assertEquals(1, loads)
        return before
    }

    private fun transaction(number: Int, loan: Int? = null, record: Int? = null, deleted: Boolean = false) =
        TransactionEntity(
            accountId = id(90),
            type = TransactionType.EXPENSE,
            amount = number.toDouble(),
            title = "Financial row $number",
            dateTime = Instant.EPOCH,
            loanId = loan?.let { id(it) },
            loanRecordId = record?.let { id(it) },
            isDeleted = deleted,
            id = id(number),
        )

    private fun tagLink(transaction: TransactionEntity) = TagAssociationEntity(
        tagId = id(80),
        associatedId = transaction.id,
        lastSyncedTime = Instant.EPOCH,
        isDeleted = transaction.isDeleted,
    )

    private fun id(number: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")
}
