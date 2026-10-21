package com.ivy.legacy.domain.deprecated.logic

import androidx.compose.ui.graphics.Color
import com.ivy.base.model.AccountSaveResult
import com.ivy.data.account.AccountBalanceWriteCommand
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
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

class AccountCreatorTest {
    private val writer = mockk<AccountBalanceWriteCommand>()
    private val creator = AccountCreator(
        accountLogic = mockk(),
        accountDao = mockk(),
        accountWrite = writer,
        currencyRepository = mockk(),
    )
    private val data = CreateAccountData("Synthetic account", "USD", Color.Blue, null, 10.0)

    @Test
    fun refreshAndSuccessWaitForTheConfirmedWrite() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshed = 0
        coEvery { writer.save(any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
        }
        val operation = async { creator.createAccount(data) { refreshed++ } }
        started.await()
        assertFalse(operation.isCompleted)
        assertEquals(0, refreshed)
        release.complete(Unit)
        assertEquals(AccountSaveResult.Saved, operation.await())
        assertEquals(1, refreshed)
        coVerify(exactly = 1) { writer.save(any(), any()) }
    }

    @Test
    fun writeFailurePropagatesWithoutAttemptingRefresh() = runBlocking {
        val failure = IllegalStateException("disk full")
        var refreshed = false
        coEvery { writer.save(any(), any()) } throws failure
        try {
            creator.createAccount(data) { refreshed = true }
            fail("Expected the write failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertFalse(refreshed)
        coVerify(exactly = 1) { writer.save(any(), any()) }
    }

    @Test
    fun refreshFailureReportsConfirmedPersistenceWithoutRetryingTheWrite() = runBlocking {
        coEvery { writer.save(any(), any()) } returns Unit
        val result = creator.createAccount(data) { error("refresh failed after commit") }
        assertEquals(AccountSaveResult.RefreshFailed, result)
        coVerify(exactly = 1) { writer.save(any(), any()) }
    }

    @Test
    fun refreshCancellationRemainsCancellation() = runBlocking {
        coEvery { writer.save(any(), any()) } returns Unit
        val cancelled = CancellationException("screen disposed")
        try {
            creator.createAccount(data) { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        coVerify(exactly = 1) { writer.save(any(), any()) }
    }

    @Test
    fun editingAlsoDistinguishesCommittedWriteFromRefreshFailure() = runBlocking {
        coEvery { writer.save(any(), any()) } returns Unit
        val account = com.ivy.legacy.datamodel.Account(
            name = "Existing synthetic account",
            color = 0,
            currency = "USD",
            archived = false,
        )
        val result = creator.editAccount(account, 20.0) { error("refresh failed after edit") }
        assertEquals(AccountSaveResult.RefreshFailed, result)
        coVerify(exactly = 1) { writer.save(any(), any()) }
    }
}
