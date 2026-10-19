package com.ivy.data.account

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivy.base.TestDispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.RepositoryMemoFactory
import com.ivy.data.repository.mapper.AccountMapper
import com.ivy.data.repository.mapper.TransactionMapper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountBalanceWriteCommandTest {
    private lateinit var db: IvyRoomDatabase
    private lateinit var observer: DataObserver
    private lateinit var accounts: AccountRepository
    private lateinit var accountMapper: AccountMapper
    private lateinit var transactionMapper: TransactionMapper
    private lateinit var command: AccountBalanceWriteCommand

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
        observer = DataObserver()
        val memos = RepositoryMemoFactory(observer, TestDispatchersProvider)
        accountMapper = AccountMapper(
            CurrencyRepository(db.settingsDao, db.writeSettingsDao, TestDispatchersProvider)
        )
        accounts = AccountRepository(
            accountMapper, db.accountDao, db.writeAccountDao, TestDispatchersProvider, memos,
        )
        transactionMapper = TransactionMapper(accounts)
        command = AccountBalanceWriteCommand(
            db, accountMapper, transactionMapper, memos, observer, TestDispatchersProvider,
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun createWithIncomeCommitsExactIdentitiesAndInvalidatesEmptyCache() = runBlocking {
        assertTrue(accounts.findAll().isEmpty())
        val account = account()
        val income = income(account)
        observing { events ->
            command.save({ account }) { supplied ->
                assertEquals(account, supplied)
                assertTrue(accounts.findAll().isEmpty())
                income
            }
            yield()
            assertEquals(listOf(DataWriteEvent.AllDataChange), events)
            assertEquals(with(accountMapper) { account.toEntity() }, db.accountDao.findById(account.id.value))
            assertEquals(with(transactionMapper) { income.toEntity() }, db.transactionDao.findById(income.id.value))
            assertEquals(listOf(account.id.value), db.accountDao.findAll().map { it.id })
            assertEquals(listOf(income.id.value), db.transactionDao.findAll().map { it.id })
            assertEquals(listOf(account), accounts.findAll())
        }
    }

    @Test
    fun failedAdjustmentInsertRollsBackNewAccountWithoutPublishingCacheOrEvent() = runBlocking {
        assertTrue(accounts.findAll().isEmpty())
        abortTransactionInserts()
        val account = account()
        observing { events ->
            expectFailure<SQLiteException> { command.save({ account }) { income(it) } }
            yield()
            assertTrue(db.accountDao.findAll().isEmpty())
            assertTrue(db.transactionDao.findAll().isEmpty())
            assertNull(accounts.findById(account.id))
            assertTrue(accounts.findAll().isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun failedAdjustmentInsertRestoresExistingAccountAndCachedValue() = runBlocking {
        val original = account()
        val originalRow = with(accountMapper) { original.toEntity() }
        db.writeAccountDao.save(originalRow)
        assertEquals(listOf(original), accounts.findAll())
        abortTransactionInserts()
        val edited = original.copy(name = NotBlankTrimmedString.unsafe("Edited"), includeInBalance = false)
        observing { events ->
            expectFailure<SQLiteException> { command.save({ edited }) { income(it) } }
            yield()
            assertEquals(listOf(originalRow), db.accountDao.findAll())
            assertTrue(db.transactionDao.findAll().isEmpty())
            assertEquals(original, accounts.findById(original.id))
            assertEquals(listOf(original), accounts.findAll())
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun adjustmentForAnotherAccountIsRejectedWithoutWritesOrEvent() = runBlocking {
        assertTrue(accounts.findAll().isEmpty())
        val account = account()
        val other = account()
        observing { events ->
            expectFailure<IllegalArgumentException> {
                command.save({ account }) { income(other) }
            }
            yield()
            assertTrue(db.accountDao.findAll().isEmpty())
            assertTrue(db.transactionDao.findAll().isEmpty())
            assertTrue(accounts.findAll().isEmpty())
            assertNull(accounts.findById(account.id))
            assertTrue(events.isEmpty())
        }
    }

    private fun abortTransactionInserts() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_adjustment BEFORE INSERT ON transactions " +
                "BEGIN SELECT RAISE(ABORT, 'injected adjustment failure'); END;"
        )
    }

    private suspend fun observing(block: suspend (List<DataWriteEvent>) -> Unit) = withTimeout(10_000) {
        coroutineScope {
            val events = mutableListOf<DataWriteEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                observer.writeEvents.collect { events.add(it) }
            }
            try { block(events) } finally { collector.cancelAndJoin() }
        }
    }

    private suspend inline fun <reified T : Exception> expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Exception) {
            if (error !is T) throw error
        }
    }

    private fun account() = Account(
        id = AccountId(UUID.randomUUID()),
        name = NotBlankTrimmedString.unsafe("Account"),
        asset = AssetCode.unsafe("USD"), color = ColorInt(1), icon = null,
        includeInBalance = true, orderNum = 1.0, archived = false,
    )

    private fun income(account: Account) = Income(
        id = TransactionId(UUID.randomUUID()), account = account.id,
        value = PositiveValue(PositiveDouble.unsafe(12.5), account.asset),
        title = null, description = null, category = null,
        time = Instant.EPOCH, settled = true,
        metadata = TransactionMetadata(null, null, null, null), tags = emptyList(),
    )
}
