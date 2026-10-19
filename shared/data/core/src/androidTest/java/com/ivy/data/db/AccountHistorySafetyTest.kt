package com.ivy.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivy.base.legacy.Theme
import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.AccountHistoryException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AccountHistorySafetyTest {
    private lateinit var db: IvyRoomDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun transferDestinationIsArchivedWithoutChangingHistoryOrBalanceChoice() = runBlocking {
        val source = AccountEntity(name = "Source", currency = "USD", color = 1)
        val destination = AccountEntity(name = "Destination", currency = "EUR", color = 2)
        db.writeAccountDao.saveMany(listOf(source, destination))
        val transfer = TransactionEntity(
            accountId = source.id,
            toAccountId = destination.id,
            type = TransactionType.TRANSFER,
            amount = 10.0,
            toAmount = 9.0,
            dateTime = Instant.EPOCH,
        )
        db.writeTransactionDao.save(transfer)

        expectHistoryError { db.writeAccountDao.deleteById(destination.id) }
        expectHistoryError { db.writeAccountDao.save(destination.copy(currency = "GBP")) }
        db.writeAccountDao.removeOrArchive(destination.id)

        assertEquals(destination.copy(archived = true), db.accountDao.findById(destination.id))
        assertEquals(listOf(transfer), db.transactionDao.findAll())
        db.writeAccountDao.save(destination.copy(archived = true, includeInBalance = false))
        assertFalse(checkNotNull(db.accountDao.findById(destination.id)).includeInBalance)
        db.writeAccountDao.save(destination)
        assertFalse(checkNotNull(db.accountDao.findById(destination.id)).archived)
    }

    @Test
    fun unusedAccountMayChangeCurrencyAndBePhysicallyRemoved() = runBlocking {
        val account = AccountEntity(name = "Unused", currency = "USD", color = 1)
        db.writeAccountDao.save(account)
        db.writeAccountDao.save(account.copy(currency = "EUR"))
        db.writeAccountDao.removeOrArchive(account.id)
        assertNull(db.accountDao.findById(account.id))
    }

    @Test
    fun activeRulePreventsArchiving() = runBlocking {
        val account = AccountEntity(name = "Scheduled", currency = "USD", color = 1)
        db.writeAccountDao.save(account)
        db.writePlannedPaymentRuleDao.save(
            PlannedPaymentRuleEntity(
                startDate = Instant.EPOCH,
                intervalN = null,
                intervalType = null,
                oneTime = true,
                type = TransactionType.EXPENSE,
                accountId = account.id,
                amount = 10.0,
            )
        )

        expectHistoryError { db.writeAccountDao.removeOrArchive(account.id) }
        assertEquals(account, db.accountDao.findById(account.id))
    }

    @Test
    fun saveManyRollsBackEarlierChangesIfLaterAccountConflicts() = runBlocking {
        val first = AccountEntity(name = "First", currency = "USD", color = 1)
        val second = AccountEntity(name = "Second", currency = "EUR", color = 2)
        db.writeAccountDao.saveMany(listOf(first, second))
        db.writeTransactionDao.save(
            TransactionEntity(
                accountId = second.id,
                type = TransactionType.INCOME,
                amount = 1.0,
                dateTime = Instant.EPOCH,
            )
        )

        expectHistoryError {
            db.writeAccountDao.saveMany(listOf(first.copy(name = "Changed"), second.copy(currency = "USD")))
        }

        assertEquals(first, db.accountDao.findById(first.id))
        assertEquals(second, db.accountDao.findById(second.id))
        assertTrue(db.transactionDao.findAll().size == 1)
    }

    @Test
    fun baseCurrencyChangeFreezesInheritedHistoryWithoutChangingExplicitCurrency() = runBlocking {
        val settings = SettingsEntity(theme = Theme.AUTO, currency = "USD", bufferAmount = 0.0, name = "")
        db.writeSettingsDao.save(settings)
        val inherited = AccountEntity(name = "Inherited", currency = null, color = 1)
        val explicit = AccountEntity(name = "Explicit", currency = "GBP", color = 2)
        db.writeAccountDao.saveMany(listOf(inherited, explicit))
        val income = TransactionEntity(
            accountId = inherited.id,
            type = TransactionType.INCOME,
            amount = 10.0,
            dateTime = Instant.EPOCH,
        )
        db.writeTransactionDao.save(income)

        db.writeSettingsDao.save(settings.copy(currency = "EUR"))

        assertEquals("EUR", db.settingsDao.findFirst().currency)
        assertEquals(inherited.copy(currency = "USD"), db.accountDao.findById(inherited.id))
        assertEquals(explicit, db.accountDao.findById(explicit.id))
        assertEquals(listOf(income), db.transactionDao.findAll())
        expectHistoryError { db.writeAccountDao.save(inherited.copy(currency = "EUR")) }
        assertEquals(inherited.copy(currency = "USD"), db.accountDao.findById(inherited.id))
        assertEquals(listOf(income), db.transactionDao.findAll())
    }

    private suspend fun expectHistoryError(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected account history protection")
        } catch (_: AccountHistoryException) {
            // Expected: the transaction must leave the database unchanged.
        }
    }
}
