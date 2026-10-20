package com.ivy.wallet.domain.deprecated.logic

import androidx.room.withTransaction
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.AccountId
import com.ivy.data.model.Income
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.legacy.datamodel.temp.toLegacyDomain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PlannedPaymentsLogicTest {
    private val db = mockk<IvyRoomDatabase>()
    private val dao = mockk<TransactionDao>()
    private val repository = mockk<TransactionRepository>(relaxed = true)
    private val mapper = mockk<TransactionMapper>()
    private val writer = mockk<WritePlannedPaymentRuleDao>(relaxed = true)
    private val clock = mockk<TimeProvider>()
    private val due = Instant.parse("2025-01-02T03:04:05Z")
    private val now = due.plusSeconds(86400)
    private var insideTransaction = false
    private var completed = false
    private lateinit var logic: PlannedPaymentsLogic

    @Before
    fun setup() {
        // Boundary injection only: this does NOT simulate or claim database rollback.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction<Any?>(any()) } coAnswers {
            insideTransaction = true
            try {
                val result = lastArg<suspend () -> Any?>().invoke()
                completed = true
                result
            } finally { insideTransaction = false }
        }
        coEvery { dao.findById(any()) } coAnswers {
            assertTrue(insideTransaction)
            null
        }
        every { clock.utcNow() } returns now
        logic = PlannedPaymentsLogic(
            mockk(relaxed = true), dao, mockk(), mockk(), mockk(), mapper,
            writer, repository, clock, db,
        )
    }

    @After
    fun cleanup() { unmockkAll() }

    private fun pending() = Income(
        id = TransactionId(UUID.randomUUID()), title = null, description = null,
        category = null, time = due, settled = false,
        metadata = TransactionMetadata(null, null, loanRecordId = null),
        tags = emptyList(), value = mockk(), account = AccountId(UUID.randomUUID()),
    )

    private fun legacy() = TransactionEntity(
        accountId = UUID.randomUUID(), type = TransactionType.INCOME,
        amount = 10.0, dueDate = due,
    ).toLegacyDomain()

    @Test
    fun mixedDomainSkipOnlyDeletesPending() = runBlocking {
        val pending = pending()
        val paid = pending().copy(settled = true)
        logic.payOrGet(listOf(paid, pending), skipTransaction = true) {
            assertTrue(completed)
            assertFalse(insideTransaction)
            assertEquals(listOf(pending.id), it.map { row -> row.id })
        }
        coVerify(exactly = 1) { repository.deleteById(pending.id) }
        coVerify(exactly = 0) { repository.deleteById(paid.id) }
    }

    @Test
    fun domainBulkSavesTransformedRowsWithOriginalDueDate() = runBlocking {
        val rows = listOf(pending(), pending())
        val saved = mutableListOf<Transaction>()
        coEvery { repository.save(capture(saved)) } coAnswers { assertTrue(insideTransaction) }
        logic.payOrGet(rows) { assertEquals(saved, it) }
        assertEquals(2, saved.size)
        saved.forEach {
            assertTrue(it.settled)
            assertEquals(due, it.metadata.paidForDateTime)
            assertNotEquals(due, it.time)
        }
    }

    @Test
    fun legacyMixedSkipOnlyDeletesPending() = runBlocking {
        val pending = legacy()
        val paid = legacy().copy(dueDate = null, dateTime = now)
        logic.payOrGetLegacy(listOf(paid, pending), skipTransaction = true) {
            assertTrue(completed)
            assertEquals(1, it.size)
            assertEquals(due, it.single().paidFor)
            assertNull(it.single().dueDate)
            assertEquals(now, it.single().dateTime)
        }
        coVerify(exactly = 1) { repository.deleteById(TransactionId(pending.id)) }
        coVerify(exactly = 0) { repository.deleteById(TransactionId(paid.id)) }
    }

    @Test
    fun legacyBulkMapsAndSavesPaidRows() = runBlocking {
        val entities = mutableListOf<TransactionEntity>()
        coEvery { with(mapper) { any<TransactionEntity>().toDomain() } } coAnswers {
            val entity = firstArg<TransactionEntity>()
            entities.add(entity)
            arrow.core.Either.Right(pending().copy(
                settled = entity.dateTime != null,
                metadata = TransactionMetadata(null, entity.paidForDateTime, loanRecordId = null),
            ))
        }
        logic.payOrGetLegacy(listOf(legacy(), legacy())) { assertTrue(completed) }
        assertEquals(2, entities.size)
        entities.forEach {
            assertEquals(due, it.paidForDateTime)
            assertEquals(now, it.dateTime)
            assertNull(it.dueDate)
        }
        coVerify(exactly = 2) { repository.save(match { it.settled && it.metadata.paidForDateTime == due }) }
    }

    @Test
    fun staleSettledSelectionIsNotDeleted() = runBlocking {
        val selected = pending()
        coEvery { dao.findById(selected.id.value) } returns TransactionEntity(
            id = selected.id.value, accountId = selected.account.value,
            type = TransactionType.INCOME, amount = 10.0, dateTime = now,
        )
        logic.payOrGet(listOf(selected), skipTransaction = true) { fail("No changed rows") }
        coVerify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun unreadableStoredPendingRowFailsInsteadOfReportingPartialSuccess() = runBlocking {
        val selected = pending()
        coEvery { dao.findById(selected.id.value) } returns TransactionEntity(
            id = selected.id.value, accountId = selected.account.value,
            type = TransactionType.INCOME, amount = 10.0, dueDate = due,
        )
        coEvery { repository.findById(selected.id) } returns null
        try {
            logic.payOrGet(listOf(selected)) { fail("Must not report success") }
            fail("Expected read failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Cannot read planned transaction"))
        }
        coVerify(exactly = 0) { repository.save(any()) }
        assertFalse(completed)
    }

    @Test
    fun laterWriteFailureEscapesBoundaryWithoutSuccessCallback() = runBlocking {
        val rows = listOf(pending(), pending())
        var writes = 0
        coEvery { repository.save(any()) } coAnswers {
            assertTrue(insideTransaction)
            writes++
            if (writes == 2) error("injected later write failure")
        }
        try {
            logic.payOrGet(rows) { fail("Must not report success") }
            fail("Expected write failure")
        } catch (expected: IllegalStateException) {
            assertEquals("injected later write failure", expected.message)
        }
        assertEquals(2, writes)
        assertFalse(completed)
        assertFalse(insideTransaction)
    }
    @Test
    fun legacyStaleSettledSelectionIsNotDeleted() = runBlocking {
        val selected = legacy()
        coEvery { dao.findById(selected.id) } returns TransactionEntity(
            id = selected.id, accountId = selected.accountId,
            type = TransactionType.INCOME, amount = 10.0, dateTime = now,
        )
        logic.payOrGetLegacy(listOf(selected), skipTransaction = true) { fail("No changed rows") }
        coVerify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun oneTimeRuleFailureIsInsideSameBoundaryAndPreventsCallback() = runBlocking {
        val ruleId = UUID.randomUUID()
        val ruleDao = mockk<com.ivy.data.db.dao.read.PlannedPaymentRuleDao>()
        coEvery { ruleDao.findById(ruleId) } returns com.ivy.data.db.entity.PlannedPaymentRuleEntity(
            id = ruleId, startDate = due, intervalN = null, intervalType = null,
            oneTime = true, type = TransactionType.INCOME,
            accountId = UUID.randomUUID(), amount = 10.0,
        )
        logic = PlannedPaymentsLogic(
            ruleDao, dao, mockk(), mockk(), mockk(), mapper, writer, repository, clock, db,
        )
        coEvery { writer.deleteById(ruleId) } coAnswers {
            assertTrue(insideTransaction)
            error("rule write failure")
        }
        val row = pending().copy(metadata = TransactionMetadata(ruleId, null, loanRecordId = null))
        try {
            logic.payOrGet(row) { fail("Must not report success") }
            fail("Expected rule failure")
        } catch (expected: IllegalStateException) {
            assertEquals("rule write failure", expected.message)
        }
        coVerify(exactly = 1) { repository.save(any()) }
        assertFalse(completed)
    }
}
