package com.ivy.legacy.domain.deprecated.logic

import com.ivy.wallet.domain.deprecated.logic.LoanCreator

import androidx.compose.ui.graphics.Color
import com.ivy.base.model.LoanSaveResult
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.loan.LoanDeletionCommand
import com.ivy.data.loan.LoanWriteCommand
import com.ivy.data.model.LoanType
import com.ivy.legacy.datamodel.Loan
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

class LoanCreatorTest {
    private val dao: LoanDao = mockk<LoanDao>()
    private val writer: LoanWriteCommand = mockk<LoanWriteCommand>()
    private val deleter: LoanDeletionCommand = mockk<LoanDeletionCommand>()
    private val creator = LoanCreator(dao, writer, deleter)
    private val item = Loan("Synthetic", 100.0, LoanType.BORROW)

    @Test
    fun creationWaitsForPersistenceBeforeRefreshing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshed = 0
        coEvery { dao.findMaxOrderNum() } returns 0.0
        coEvery { writer.saveLoan(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            LoanSaveResult.Saved
        }
        val data = CreateLoanData(
            "New",
            10.0,
            LoanType.LEND,
            Color.Blue,
            null,
            note = null,
            dateTime = LocalDateTime.of(2026, 1, 1, 12, 0)
        )
        val save = async { creator.create(data, {}) { refreshed++ } }
        started.await()
        assertFalse(save.isCompleted)
        assertEquals(0, refreshed)
        release.complete(Unit)
        assertEquals(LoanSaveResult.Saved, save.await())
        assertEquals(1, refreshed)
    }

    @Test
    fun `edit writer IllegalStateException propagates without refresh`() = runBlocking {
        val failure = IllegalStateException("boom")
        coEvery { writer.saveLoan(any(), any(), any()) } throws failure
        var refreshed = false
        try {
            creator.edit(item, item, {}, { refreshed = true })
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertFalse(refreshed)
    }

    @Test
    fun `edit refresh failure returns RefreshFailed`() = runBlocking {
        coEvery { writer.saveLoan(any(), any(), any()) } returns LoanSaveResult.Saved
        val result = creator.edit(item, item, {}, { throw IllegalStateException("ui") })
        assertEquals(LoanSaveResult.RefreshFailed, result)
        coVerify(exactly = 1) { writer.saveLoan(any(), any(), any()) }
    }

    @Test
    fun `edit writer CancellationException propagates without refresh`() = runBlocking {
        val cancelled = CancellationException("gone")
        coEvery { writer.saveLoan(any(), any(), any()) } throws cancelled
        var refreshed = false
        try {
            creator.edit(item, item, {}, { refreshed = true })
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
        assertFalse(refreshed)
    }
    @Test
    fun `edit forwards updated entity with original snapshot`() = runBlocking {
        coEvery { writer.saveLoan(any(), any(), any()) } returns LoanSaveResult.Saved
        val updated = item.copy(amount = 200.0)
        creator.edit(item, updated, {}, {})
        coVerify(exactly = 1) { writer.saveLoan(updated.toEntity(), item.toEntity(), any()) }
    }
}
