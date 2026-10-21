package com.ivy.wallet.domain.deprecated.logic

import com.ivy.base.model.LoanSaveResult
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.loan.LoanDeletionCommand
import com.ivy.data.loan.LoanWriteCommand
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.datamodel.LoanType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class LoanCreatorTest {
    private val dao: LoanDao = mockk<LoanDao>()
    private val writer: LoanWriteCommand = mockk<LoanWriteCommand>()
    private val deleter: LoanDeletionCommand = mockk<LoanDeletionCommand>()
    private val creator = LoanCreator(dao, writer, deleter)
    private val item = Loan("Synthetic", 100.0, LoanType.BORROW)

    @Test
    fun `edit writer IllegalStateException propagates without refresh`() = runBlocking {
        val failure = IllegalStateException("boom")
        coEvery { writer.saveLoan(any(), any(), any()) } throws failure
        var refreshed = false
        try {
            creator.edit(item, {}, { refreshed = true })
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertFalse(refreshed)
    }

    @Test
    fun `edit refresh failure returns RefreshFailed`() = runBlocking {
        coEvery { writer.saveLoan(any(), any(), any()) } returns LoanSaveResult.Saved
        val result = creator.edit(item, {}, { throw IllegalStateException("ui") })
        assertEquals(LoanSaveResult.RefreshFailed, result)
        coVerify(exactly = 1) { writer.saveLoan(any(), any(), any()) }
    }

    @Test
    fun `edit writer CancellationException propagates without refresh`() = runBlocking {
        val cancelled = CancellationException("gone")
        coEvery { writer.saveLoan(any(), any(), any()) } throws cancelled
        var refreshed = false
        try {
            creator.edit(item, {}, { refreshed = true })
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
        assertFalse(refreshed)
    }
}
