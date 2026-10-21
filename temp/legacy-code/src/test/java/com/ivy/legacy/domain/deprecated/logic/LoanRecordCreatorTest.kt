package com.ivy.wallet.domain.deprecated.logic

import com.ivy.base.model.LoanRecordType
import com.ivy.base.model.LoanSaveResult
import com.ivy.data.loan.LoanDeletionCommand
import com.ivy.data.loan.LoanWriteCommand
import com.ivy.data.model.LoanType
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanRecordData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class LoanRecordCreatorTest {
    private val writer = mockk<LoanWriteCommand>()
    private val deleter = mockk<LoanDeletionCommand>()
    private val creator = LoanRecordCreator(writer, deleter)
    private val loan = Loan("Synthetic", 100.0, LoanType.BORROW)
    private val record = LoanRecord(loan.id, 10.0, dateTime = Instant.EPOCH)
    private val noopWrite: suspend (LoanRecord) -> Unit = {}

    @Test
    fun creationWaitsForPersistenceBeforeRefreshing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshed = 0
        coEvery { writer.saveRecord(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            LoanSaveResult.Saved
        }
        val data = CreateLoanRecordData(null, 10.0, Instant.EPOCH, loanRecordType = LoanRecordType.DECREASE)
        val save = async { creator.create(loan, data, noopWrite) { refreshed++ } }
        started.await()
        assertFalse(save.isCompleted)
        assertEquals(0, refreshed)
        release.complete(Unit)
        assertEquals(LoanSaveResult.Saved, save.await())
        assertEquals(1, refreshed)
    }

    @Test
    fun saveThrowsNoRefresh() = runBlocking {
        coEvery { writer.saveRecord(any(), any(), any(), any()) } throws IllegalStateException("db")
        var refresh = 0
        try {
            creator.edit(record, loan, noopWrite) { refresh++ }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) { }
        assertEquals(0, refresh)
    }

    @Test
    fun refreshThrowsReturnsRefreshFailed() = runBlocking {
        coEvery { writer.saveRecord(any(), any(), any(), any()) } returns LoanSaveResult.Saved
        val result = creator.edit(record, loan, noopWrite) { throw IllegalStateException("ui") }
        assertEquals(LoanSaveResult.RefreshFailed, result)
        coVerify(exactly = 1) { writer.saveRecord(any(), any(), any(), any()) }
    }

    @Test
    fun cancellationPropagatesNoRefresh() = runBlocking {
        coEvery { writer.saveRecord(any(), any(), any(), any()) } throws CancellationException("cancel")
        var refresh = 0
        try {
            creator.edit(record, loan, noopWrite) { refresh++ }
            fail("expected CancellationException")
        } catch (_: CancellationException) { }
        assertEquals(0, refresh)
    }
}
