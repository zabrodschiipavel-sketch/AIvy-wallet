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
import com.ivy.data.db.entity.CategoryEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoanWriteCommandTest {
    private lateinit var db: IvyRoomDatabase
    private lateinit var observer: DataObserver
    private lateinit var command: LoanWriteCommand
    private val account = AccountEntity("Synthetic", "USD", color = 0, id = id(1))
    private val loan = LoanEntity("Loan", 100.0, LoanType.BORROW, accountId = id(1), id = id(2))
    private val record = LoanRecordEntity(id(2), 10.0, dateTime = Instant.EPOCH, accountId = id(1), id = id(3))
    private val category = CategoryEntity("Loans", color = 0, id = id(4))
    private val transaction = TransactionEntity(
        accountId = id(1),
        type = TransactionType.INCOME,
        amount = 100.0,
        categoryId = id(4),
        dateTime = Instant.EPOCH,
        loanId = id(2),
        id = id(5),
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
        observer = DataObserver()
        command = LoanWriteCommand(
            db, RepositoryMemoFactory(observer, TestDispatchersProvider), observer, TestDispatchersProvider,
        )
        db.writeAccountDao.save(account)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun createLoanPublishesOnlyTheCommittedGraphAndPreservesCoroutineTransactionContext() = runBlocking {
        observing {
            assertEquals(
                LoanSaveResult.Saved,
                command.saveLoan(loan, true) {
                    withContext(Dispatchers.Default) { linkedWrites() }
                }
            )
            assertEquals(graph(), receive())
        }
        assertEquals(listOf(loan), db.loanDao.findAllForRestore())
        assertEquals(listOf(category), db.categoryDao.findAll())
        assertEquals(listOf(transaction), db.transactionDao.findAllForRestore())
    }

    @Test
    fun createLoanRollsBackAtEveryWriteIncludingAfterTheLastWrite() = runBlocking {
        for (table in listOf("loans", "categories", "transactions", null)) {
            val before = graph()
            abortOnInsert(table)
            expectFailure {
                command.saveLoan(loan, true) {
                    linkedWrites()
                    if (table == null) error("failure after final write")
                }
            }
            dropAbort()
            assertEquals(before, graph())
        }
    }

    @Test
    fun editLoanAndRecalculatedRecordsRollBackTogether() = runBlocking {
        seedLoanAndRecord()
        linkedWrites()
        val before = graph()
        expectFailure {
            command.saveLoan(loan.copy(amount = 200.0), false) {
                db.writeLoanRecordDao.save(record.copy(convertedAmount = 20.0))
                db.writeTransactionDao.save(transaction.copy(amount = 200.0))
                error("failure after all edits")
            }
        }
        assertEquals(before, graph())
    }

    @Test
    fun createRecordRollsBackAtEveryWrite() = runBlocking {
        db.writeLoanDao.save(loan)
        for (table in listOf("loan_records", "categories", "transactions", null)) {
            val before = graph()
            abortOnInsert(table)
            expectFailure {
                command.saveRecord(record, true, loan) {
                    linkedWrites(transaction.copy(loanRecordId = record.id))
                    if (table == null) error("failure after final write")
                }
            }
            dropAbort()
            assertEquals(before, graph())
        }
    }

    @Test
    fun editRecordAndLinkedTransactionCommitTogether() = runBlocking {
        seedLoanAndRecord()
        val updated = record.copy(amount = 25.0, interest = true, convertedAmount = 30.0)
        observing {
            command.saveRecord(updated, false, loan) {
                linkedWrites(transaction.copy(amount = 25.0, loanRecordId = record.id))
            }
            assertEquals(graph(), receive())
        }
        assertEquals(updated, db.loanRecordDao.findById(record.id))
        assertEquals(25.0, db.transactionDao.findAllForRestore().single().amount, 0.0)
    }

    @Test
    fun editRecordFailureRestoresPreviousFinancialValues() = runBlocking {
        seedLoanAndRecord()
        linkedWrites(transaction.copy(loanRecordId = record.id))
        db.writeTagDao.save(
            listOf(
                TagEntity(
                    id = id(6), name = "Shared", description = null, color = 0, icon = null,
                    orderNum = 0.0, dateTime = Instant.EPOCH, isDeleted = false, lastSyncedTime = Instant.EPOCH,
                )
            )
        )
        db.writeTagAssociationDao.save(
            listOf(
                TagAssociationEntity(
                    tagId = id(6),
                    associatedId = transaction.id,
                    lastSyncedTime = Instant.EPOCH,
                    isDeleted = false,
                )
            )
        )
        val before = graph()
        expectFailure {
            command.saveRecord(record.copy(amount = 25.0), false, loan) {
                db.writeTransactionDao.deleteById(transaction.id)
                error("failure after financial row deletion")
            }
        }
        assertEquals(before, graph())
    }

    @Test
    fun cancellationRollsBackAndDoesNotPublishSuccess() = runBlocking {
        val before = graph()
        observing {
            try {
                command.saveLoan(loan, true) {
                    linkedWrites()
                    throw CancellationException("disposed before commit")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(before, graph())
                assertEquals(true, tryReceive().isFailure)
            }
        }
    }

    @Test
    fun duplicateCreationAndMissingOrDeletedEditsAreRejected() = runBlocking {
        command.saveLoan(loan, true) {}
        val before = graph()
        expectInvalid { command.saveLoan(loan, true) {} }
        expectInvalid { command.saveLoan(loan.copy(id = id(90)), false) {} }
        assertEquals(before, graph())
        db.writeLoanDao.save(loan.copy(isDeleted = true))
        expectInvalid { command.saveLoan(loan, false) {} }
        expectInvalid { command.saveRecord(record, true, loan) {} }
    }

    @Test
    fun staleParentAndChangedRecordOwnershipAreRejected() = runBlocking {
        seedLoanAndRecord()
        expectInvalid { command.saveRecord(record, false, loan.copy(amount = 150.0)) {} }
        val otherLoan = loan.copy(id = id(90))
        db.writeLoanDao.save(otherLoan)
        expectInvalid { command.saveRecord(record.copy(loanId = otherLoan.id), false, otherLoan) {} }
        assertEquals(record, db.loanRecordDao.findById(record.id))
    }

    @Test
    fun invalidMoneyAndMissingAccountCannotReachDependentWrites() = runBlocking {
        for (amount in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            expectInvalid { command.saveLoan(loan.copy(amount = amount), true) { fail("must not write") } }
        }
        expectInvalid { command.saveLoan(loan.copy(name = "  "), true) {} }
        expectInvalid { command.saveLoan(loan.copy(accountId = id(90)), true) {} }
        assertEquals(emptyList<LoanEntity>(), db.loanDao.findAllForRestore())
    }

    @Test
    fun saveRecordWithoutParentRejectsAndLeavesGraphUnchanged() = runBlocking {
        val before = graph()
        expectInvalid {
            command.saveRecord(record, true, loan) {
                fail("must not write")
            }
        }
        assertEquals(before, graph())
    }

    @Test
    fun saveWithDeletedAccountRejectsDependentsAndLeavesGraphUnchanged() = runBlocking {
        seedLoanAndRecord()
        db.writeAccountDao.save(account.copy(isDeleted = true))
        val before = graph()
        expectInvalid {
            command.saveLoan(loan, false) {
                fail("must not write loan")
            }
        }
        expectInvalid {
            command.saveRecord(record, false, loan) {
                fail("must not write record")
            }
        }
        assertEquals(before, graph())
    }

    @Test
    fun saveRecordWithInvalidAmountsRejectsAndLeavesGraphUnchanged() = runBlocking {
        db.writeLoanDao.save(loan)
        val before = graph()
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { amount ->
            expectInvalid {
                command.saveRecord(record.copy(amount = amount), true, loan) {
                    fail("must not write")
                }
            }
        }
        expectInvalid {
            command.saveRecord(record.copy(convertedAmount = Double.NaN), true, loan) {
                fail("must not write")
            }
        }
        assertEquals(before, graph())
    }

    @Test
    fun archivedAccountRejectsNewLoanAndNewRecord() = runBlocking {
        db.writeAccountDao.save(account.copy(archived = true))
        expectInvalid { command.saveLoan(loan, true) { fail("must not write") } }
        db.writeLoanDao.save(loan)
        val before = graph()
        expectInvalid { command.saveRecord(record, true, loan) { fail("must not write") } }
        assertEquals(before, graph())
    }

    @Test
    fun archivedHistoryCanBeEditedButCannotMoveToAnotherArchivedAccount() = runBlocking {
        seedLoanAndRecord()
        db.writeAccountDao.save(account.copy(archived = true))
        val updatedLoan = loan.copy(amount = 200.0)
        command.saveLoan(updatedLoan, false) {}
        command.saveRecord(record.copy(amount = 20.0), false, updatedLoan) {}
        assertEquals(updatedLoan, db.loanDao.findById(loan.id))
        assertEquals(20.0, db.loanRecordDao.findById(record.id)!!.amount, 0.0)
        val other = account.copy(id = id(90), archived = true)
        db.writeAccountDao.save(other)
        val before = graph()
        expectInvalid { command.saveLoan(updatedLoan.copy(accountId = other.id), false) {} }
        expectInvalid { command.saveRecord(record.copy(accountId = other.id), false, updatedLoan) {} }
        assertEquals(before, graph())
    }

    private suspend fun seedLoanAndRecord() {
        db.writeLoanDao.save(loan)
        db.writeLoanRecordDao.save(record)
    }

    private suspend fun linkedWrites(row: TransactionEntity = transaction) {
        db.writeCategoryDao.save(category)
        db.writeTransactionDao.save(row)
    }

    private suspend fun graph(): List<Any> = listOf(
        db.accountDao.findAll(),
        db.loanDao.findAllForRestore(),
        db.loanRecordDao.findAllForRestore(),
        db.categoryDao.findAll(),
        db.transactionDao.findAllForRestore(),
        db.tagAssociationDao.findAll(),
        db.tagDao.findAll(),
    )

    private fun abortOnInsert(table: String?) {
        if (table != null) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER injected_abort BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'injected write failure'); END;"
            )
        }
    }

    private fun dropAbort() { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS injected_abort") }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected failure")
        } catch (_: SQLiteException) {
            // Trigger reached a real SQLite write.
        } catch (_: IllegalStateException) {
            // Fault injected after a successful dependent write.
        }
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid command")
        } catch (_: IllegalArgumentException) {
            // Validation must happen inside the transaction, before writes.
        }
    }

    private suspend fun observing(block: suspend Channel<List<Any>>.() -> Unit) = withTimeout(10_000) {
        coroutineScope {
            val committed = Channel<List<Any>>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                observer.writeEvents.collect {
                    assertEquals(DataWriteEvent.AllDataChange, it)
                    // A distinct coroutine re-enters Room; publishing under its lock deadlocks.
                    committed.send(graph())
                }
            }
            try { committed.block() } finally {
                collector.cancelAndJoin()
                committed.close()
            }
        }
    }

    private fun id(number: Int): UUID = UUID(0, number.toLong())
}
