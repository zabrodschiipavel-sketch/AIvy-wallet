package com.ivy.data.loan

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivy.base.TestDispatchersProvider
import com.ivy.base.model.LoanSaveResult
import com.ivy.base.model.TransactionType
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.LoanType
import com.ivy.data.repository.RepositoryMemoFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoanTransactionEditCommandTest {
    private lateinit var db: IvyRoomDatabase
    private lateinit var observer: DataObserver
    private lateinit var command: LoanTransactionEditCommand
    private val account = AccountEntity("Synthetic", "USD", color = 0, id = id(1))
    private val loan = LoanEntity("Loan", 100.0, LoanType.BORROW, accountId = id(1), id = id(2))
    private val record = LoanRecordEntity(id(2), 10.0, dateTime = Instant.EPOCH, accountId = id(1), id = id(3))
    private val transaction = TransactionEntity(
        id(1), TransactionType.INCOME, 100.0, dateTime = Instant.EPOCH, loanId = id(2), id = id(4),
        attachmentUrl = "synthetic://attachment",
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
        observer = DataObserver()
        command = LoanTransactionEditCommand(
            db, RepositoryMemoFactory(observer, TestDispatchersProvider), observer, TestDispatchersProvider,
        )
        db.writeAccountDao.save(account)
        db.writeLoanDao.save(loan)
        db.writeLoanRecordDao.save(record)
        db.writeTransactionDao.save(transaction)
        db.writeTagAssociationDao.save(TagAssociationEntity(id(90), transaction.id, Instant.EPOCH, false))
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun commitsParentAndFinancialRowAndPublishesOutsideRoomLock() = runBlocking {
        observing {
            val updated = transaction.copy(amount = 150.0)
            assertEquals(LoanSaveResult.Saved, command.save(transaction, updated) {
                withContext(Dispatchers.Default) { db.writeLoanDao.save(loan.copy(amount = 150.0)) }
            })
            assertEquals(graph(), receive())
            assertEquals(updated, command.snapshot(transaction.id))
            assertFalse(tryReceive().isSuccess)
        }
    }

    @Test
    fun failureAtEachWriteRollsBackLoanRecordsFinancialRowAndTags() = runBlocking {
        for (table in listOf("loans", "loan_records", "transactions", null)) {
            val before = graph()
            if (table != null) db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER injected_abort BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected failure'); END;"
            )
            observing {
                expectFailure {
                    command.save(transaction, transaction.copy(amount = 200.0)) {
                        db.writeLoanDao.save(loan.copy(amount = 200.0))
                        db.writeLoanRecordDao.save(record.copy(convertedAmount = 20.0))
                        if (table == null) error("after related writes")
                    }
                }
                assertEquals(before, graph())
                assertFalse(tryReceive().isSuccess)
            }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS injected_abort")
        }
    }

    @Test
    fun repaymentAndFinancialRowRollBackTogether() = runBlocking {
        val repayment = transaction.copy(
            type = TransactionType.EXPENSE, amount = 10.0, loanRecordId = record.id,
        )
        db.writeTransactionDao.save(repayment)
        val before = graph()
        expectFailure {
            command.save(repayment, repayment.copy(amount = 40.0)) {
                db.writeLoanRecordDao.save(record.copy(amount = 40.0))
                error("after repayment write")
            }
        }
        assertEquals(before, graph())
        command.save(repayment, repayment.copy(amount = 40.0)) {
            db.writeLoanRecordDao.save(record.copy(amount = 40.0))
        }
        assertEquals(40.0, db.loanRecordDao.findById(record.id)!!.amount, 0.0)
        assertEquals(40.0, command.snapshot(transaction.id)!!.amount, 0.0)
    }

    @Test
    fun staleOrDeletedTransactionNeverRunsAssociatedWrites() = runBlocking {
        for (row in listOf(transaction.copy(amount = 99.0), transaction.copy(isDeleted = true))) {
            db.writeTransactionDao.save(row)
            val before = graph()
            expectInvalid { command.save(transaction, transaction.copy(amount = 200.0)) { fail("callback") } }
            assertEquals(before, graph())
        }
    }

    @Test
    fun rejectsInvalidAmountsTransfersAndChangedLinksBeforeCallback() = runBlocking {
        val invalid = listOf(
            transaction.copy(amount = Double.NaN), transaction.copy(amount = Double.POSITIVE_INFINITY),
            transaction.copy(amount = 0.0), transaction.copy(amount = -1.0),
            transaction.copy(type = TransactionType.TRANSFER, toAccountId = id(8)),
            transaction.copy(loanId = id(9)), transaction.copy(loanRecordId = record.id),
            transaction.copy(id = id(10)), transaction.copy(dateTime = null, dueDate = Instant.EPOCH),
        )
        val before = graph()
        for (updated in invalid) expectInvalid { command.save(transaction, updated) { fail("callback") } }
        assertEquals(before, graph())
    }

    @Test
    fun cancellationRollsBackDependentWritesAndPublishesNothing() = runBlocking {
        val before = graph()
        observing {
            try {
                command.save(transaction, transaction.copy(amount = 200.0)) {
                    db.writeLoanDao.save(loan.copy(amount = 200.0))
                    throw CancellationException("cancel after dependent write")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(before, graph())
                assertFalse(tryReceive().isSuccess)
            }
        }
    }

    private suspend fun graph(): List<Any> = listOf(
        db.accountDao.findAll(), db.loanDao.findAllForRestore(), db.loanRecordDao.findAllForRestore(),
        db.transactionDao.findAllForRestore(), db.tagAssociationDao.findAll(),
    )

    @Test
    fun requiresMatchingAssociatedWritesAndAllowsAtomicAccountMove() = runBlocking {
        val other = account.copy(id = id(20), currency = "EUR")
        db.writeAccountDao.save(other)
        val before = graph()
        val moved = transaction.copy(accountId = other.id)
        expectInvalid { command.save(transaction, moved) {} }
        expectInvalid { command.save(transaction, transaction.copy(amount = 200.0)) {} }
        assertEquals(before, graph())
        command.save(transaction, moved) { db.writeLoanDao.save(loan.copy(accountId = other.id)) }
        assertEquals(moved, command.snapshot(transaction.id))
        assertEquals(other.id, db.loanDao.findById(loan.id)!!.accountId)
    }

    @Test
    fun rejectsDeletedParentsAndRecordOwnershipMismatch() = runBlocking {
        db.writeLoanDao.save(loan.copy(isDeleted = true))
        val deletedGraph = graph()
        expectInvalid { command.save(transaction, transaction) { fail("callback") } }
        assertEquals(deletedGraph, graph())
        db.writeLoanDao.save(loan)
        val repayment = transaction.copy(
            type = TransactionType.EXPENSE, amount = 10.0, loanRecordId = record.id,
        )
        db.writeTransactionDao.save(repayment)
        for (invalidRecord in listOf(record.copy(isDeleted = true), record.copy(loanId = id(88)))) {
            db.writeLoanRecordDao.save(invalidRecord)
            val before = graph()
            expectInvalid { command.save(repayment, repayment) { fail("callback") } }
            assertEquals(before, graph())
        }
    }

    private suspend fun observing(block: suspend Channel<List<Any>>.() -> Unit) = withTimeout(10_000) {
        coroutineScope {
            val events = Channel<List<Any>>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                observer.writeEvents.collect {
                    assertEquals(DataWriteEvent.AllDataChange, it)
                    events.send(graph())
                }
            }
            try { events.block() } finally { collector.cancelAndJoin(); events.close() }
        }
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { /* validation */ }
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected rollback") } catch (_: SQLiteException) { /* SQLite trigger */ }
        catch (_: IllegalStateException) { /* fault after dependent write */ }
    }

    private fun id(value: Int): UUID = UUID(0, value.toLong())
}
