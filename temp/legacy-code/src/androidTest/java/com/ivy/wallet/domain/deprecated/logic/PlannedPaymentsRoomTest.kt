package com.ivy.wallet.domain.deprecated.logic

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ivy.base.TestDispatchersProvider
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.DataObserver
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.RepositoryMemoFactory
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.AccountMapper
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.legacy.datamodel.temp.toLegacyDomain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlannedPaymentsRoomTest {
    private lateinit var db: IvyRoomDatabase
    private lateinit var mapper: TransactionMapper
    private lateinit var logic: PlannedPaymentsLogic
    private val due = Instant.parse("2025-01-02T03:04:05Z")
    private val now = Instant.parse("2025-02-03T04:05:06Z")
    private val accountId = AccountId(id(1))
    private val rules = (1..2).map { index ->
        PlannedPaymentRuleEntity(
            id = id(10 + index),
            startDate = due,
            intervalN = null,
            intervalType = null,
            oneTime = true,
            type = TransactionType.INCOME,
            accountId = accountId.value,
            amount = index * 10.0,
        )
    }
    private val pending = rules.mapIndexed { index, rule ->
        TransactionEntity(
            id = id(20 + index),
            accountId = accountId.value,
            type = TransactionType.INCOME,
            amount = rule.amount,
            dueDate = due.plusSeconds(index * 3600L),
            dateTime = null,
            recurringRuleId = rule.id,
        )
    }
    private val settled = TransactionEntity(
        id = id(30),
        accountId = accountId.value,
        type = TransactionType.INCOME,
        amount = 99.0,
        dateTime = due.minusSeconds(86400),
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), IvyRoomDatabase::class.java,
        ).build()
        val memos = RepositoryMemoFactory(DataObserver(), TestDispatchersProvider)
        val accountMapper = AccountMapper(
            CurrencyRepository(db.settingsDao, db.writeSettingsDao, TestDispatchersProvider, memos)
        )
        val accounts = AccountRepository(
            accountMapper,
            db.accountDao,
            db.writeAccountDao,
            TestDispatchersProvider,
            memos,
        )
        mapper = TransactionMapper(accounts)
        val repository = TransactionRepository(
            mapper,
            db.transactionDao,
            db.writeTransactionDao,
            TestDispatchersProvider,
            mockk(relaxed = true), // These untagged reads/writes do not use TagRepository.
        )
        val clock = mockk<TimeProvider>()
        every { clock.utcNow() } returns now
        logic = PlannedPaymentsLogic(
            db.plannedPaymentRuleDao, db.transactionDao, db.settingsDao, mockk(),
            db.accountDao, mapper, db.writePlannedPaymentRuleDao, repository, clock, db,
        )
        withTimeout(10_000) {
            val account = Account(
                id = accountId,
                name = NotBlankTrimmedString.unsafe("USD account"),
                asset = AssetCode.unsafe("USD"),
                color = ColorInt(1),
                icon = null,
                includeInBalance = true,
                orderNum = 1.0,
                archived = false,
            )
            db.writeAccountDao.save(with(accountMapper) { account.toEntity() })
            rules.forEach { db.writePlannedPaymentRuleDao.save(it) }
            (pending + settled).forEach { db.writeTransactionDao.save(it) }
        }
    }

    @After
    fun tearDown() { db.close() }

    @Test fun domainBulkSkipLeavesSettledUntouched() = skip(false)

    @Test fun legacyBulkSkipLeavesSettledUntouched() = skip(true)

    @Test fun domainBulkSettleKeepsOriginalDueDates() = settle(false)

    @Test fun legacyBulkSettleKeepsOriginalDueDates() = settle(true)

    @Test fun domainLaterRuleFailureRollsBackSettlement() = rollback(false, false)

    @Test fun legacyLaterRuleFailureRollsBackSettlement() = rollback(true, false)

    @Test fun domainLaterRuleFailureRollsBackSkip() = rollback(false, true)

    @Test fun legacyLaterRuleFailureRollsBackSkip() = rollback(true, true)

    private fun skip(legacy: Boolean) = runBlocking {
        withTimeout(10_000) {
            var callbacks = 0
            bulk(legacy, skip = true) { callbacks++ }
            assertEquals(1, callbacks)
            assertRows(listOf(settled), emptyList())
        }
    }

    private fun settle(legacy: Boolean) = runBlocking {
        withTimeout(10_000) {
            var callbacks = 0
            bulk(legacy, skip = false) { callbacks++ }
            assertEquals(1, callbacks)
            val paid = pending.map { original ->
                val row = requireNotNull(db.transactionDao.findById(original.id))
                assertNotNull(row.dateTime)
                if (legacy) assertEquals(now, row.dateTime)
                assertEquals(original.dueDate, row.paidForDateTime)
                assertNull(row.dueDate)
                original.copy(
                    dateTime = row.dateTime,
                    dueDate = null,
                    paidForDateTime = original.dueDate,
                    isSynced = true,
                )
            }
            assertRows(paid + settled, emptyList())
        }
    }

    private fun rollback(legacy: Boolean, skip: Boolean) = runBlocking {
        withTimeout(10_000) {
            // Abort on the SECOND rule deletion. The trigger itself verifies that the
            // first payment write AND its one-time rule deletion have already happened.
            val firstChanged = if (skip) {
                "NOT EXISTS (SELECT 1 FROM transactions WHERE id = '${pending[0].id}')"
            } else {
                "EXISTS (SELECT 1 FROM transactions WHERE id = '${pending[0].id}' AND dateTime IS NOT NULL)"
            }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_later_rule BEFORE DELETE ON planned_payment_rules " +
                    "WHEN OLD.id = '${rules[1].id}' BEGIN " +
                    "SELECT CASE WHEN $firstChanged AND NOT EXISTS " +
                    "(SELECT 1 FROM planned_payment_rules WHERE id = '${rules[0].id}') " +
                    "THEN RAISE(ABORT, 'later rule failure after first writes') " +
                    "ELSE RAISE(ABORT, 'first writes not observed') END; END;"
            )
            var callbacks = 0
            try {
                bulk(legacy, skip) { callbacks++ }
                fail("Expected SQLite trigger failure")
            } catch (error: SQLiteException) {
                assertTrue(error.message.orEmpty().contains("later rule failure after first writes"))
            }
            assertEquals(0, callbacks)
            // Raw DAO snapshots include deleted rows: no repository cache or fake rollback.
            assertRows(pending + settled, rules)
        }
    }

    private suspend fun bulk(legacy: Boolean, skip: Boolean, onSuccess: () -> Unit) {
        val selected = listOf(settled) + pending
        if (legacy) {
            logic.payOrGetLegacy(selected.map { it.toLegacyDomain() }, skipTransaction = skip) {
                assertEquals(pending.map { row -> row.id }, it.map { row -> row.id })
                onSuccess()
            }
        } else {
            val domain = selected.map { with(mapper) { it.toDomain() }.getOrNull()!! }
            logic.payOrGet(domain, skipTransaction = skip) {
                assertEquals(pending.map { row -> row.id }, it.map { row -> row.id.value })
                onSuccess()
            }
        }
    }

    private suspend fun assertRows(
        transactions: List<TransactionEntity>,
        expectedRules: List<PlannedPaymentRuleEntity>,
    ) {
        assertEquals(transactions.associateBy { it.id }, db.transactionDao.findAllForRestore().associateBy { it.id })
        assertEquals(
            expectedRules.associateBy { it.id },
            db.plannedPaymentRuleDao.findAllForRestore().associateBy { it.id }
        )
    }

    private fun id(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
