package com.ivy.ui.account

import com.ivy.base.model.AccountSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSaveControllerTest {
    @Test
    fun savesOnceAndWaitsForConfirmationBeforeClosing() = runTest {
        val gate = CompletableDeferred<AccountSaveResult>()
        var writes = 0
        var closed = 0
        val controller = controller { closed++ }
        repeat(2) {
            controller.submit {
                writes++
                gate.await()
            }
        }
        assertTrue(controller.isSaving)
        assertEquals(0, closed)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(0, closed)
        gate.complete(AccountSaveResult.Saved)
        runCurrent()
        assertEquals(1, closed)
        assertFalse(controller.isSaving)
        controller.submit {
            writes++
            AccountSaveResult.Saved
        }
        runCurrent()
        assertEquals(1, writes)
    }

    @Test
    fun failedWriteKeepsEditorOpenAndAllowsRetry() = runTest {
        var closed = 0
        var writes = 0
        val controller = controller { closed++ }
        controller.submit {
            writes++
            error("disk full")
        }
        runCurrent()
        assertTrue(controller.failed)
        assertFalse(controller.isSaving)
        assertEquals(0, closed)
        controller.submit {
            writes++
            AccountSaveResult.Saved
        }
        assertFalse(controller.failed)
        runCurrent()
        assertEquals(2, writes)
        assertEquals(1, closed)
    }

    @Test
    fun refreshFailureIsConfirmedSaveAndCannotWriteAgain() = runTest {
        val results = mutableListOf<AccountSaveResult>()
        var writes = 0
        val controller = controller { results.add(it) }
        controller.submit {
            writes++
            AccountSaveResult.RefreshFailed
        }
        runCurrent()
        assertEquals(listOf(AccountSaveResult.RefreshFailed), results)
        assertFalse(controller.failed)
        controller.submit {
            writes++
            AccountSaveResult.Saved
        }
        runCurrent()
        assertEquals(1, writes)
    }

    @Test
    fun cancellationDoesNotShowWriteFailureOrCloseEditor() = runTest {
        var closed = 0
        val controller = controller { closed++ }
        controller.submit { throw CancellationException("screen disposed") }
        runCurrent()
        assertFalse(controller.isSaving)
        assertFalse(controller.failed)
        assertEquals(0, closed)
    }

    @Test
    fun lateCompletionFromDisposedEditorDoesNotCloseReplacement() = runTest {
        val oldGate = CompletableDeferred<AccountSaveResult>()
        val newGate = CompletableDeferred<AccountSaveResult>()
        var oldClosed = 0
        var newClosed = 0
        val old = controller { oldClosed++ }
        val replacement = controller { newClosed++ }
        old.submit { withContext(NonCancellable) { oldGate.await() } }
        runCurrent()
        old.dispose()
        replacement.submit { newGate.await() }
        runCurrent()
        oldGate.complete(AccountSaveResult.Saved)
        runCurrent()
        assertEquals(0, oldClosed)
        assertEquals(0, newClosed)
        assertTrue(replacement.isSaving)
        newGate.complete(AccountSaveResult.Saved)
        runCurrent()
        assertEquals(1, newClosed)
    }

    @Test
    fun changedModalIdentityRejectsStaleCompletionEvenBeforeDisposal() = runTest {
        var current = true
        var closed = 0
        val gate = CompletableDeferred<AccountSaveResult>()
        val controller = controller(isCurrent = { current }) { closed++ }
        controller.submit { gate.await() }
        runCurrent()
        current = false
        gate.complete(AccountSaveResult.Saved)
        runCurrent()
        assertEquals(0, closed)
        assertFalse(controller.failed)
    }

    @Test
    fun disposingBeforeLaunchPreventsTheWrite() = runTest {
        var writes = 0
        val controller = controller { error("must not close") }
        controller.submit {
            writes++
            AccountSaveResult.Saved
        }
        controller.dispose()
        runCurrent()
        assertEquals(0, writes)
    }

    @Test
    fun inactiveScopeRejectsSubmissionWithoutEnteringSavingState() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        scope.cancel()
        val controller = AccountSaveController(scope, { true }, { error("must not close") })
        controller.submit { error("must not write") }
        assertFalse(controller.isSaving)
    }

    @Test
    fun reusableSuggestionFlowAllowsDistinctConfirmedWrites() = runTest {
        var saved = 0
        val controller = AccountSaveController(
            scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])),
            isCurrent = { true },
            onSaved = { saved++ },
            singleSave = false,
        )
        repeat(2) {
            controller.submit { AccountSaveResult.Saved }
            runCurrent()
        }
        assertEquals(2, saved)
    }

    private fun TestScope.controller(
        isCurrent: () -> Boolean = { true },
        onSaved: (AccountSaveResult) -> Unit,
    ) = AccountSaveController(
        scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])),
        isCurrent = isCurrent,
        onSaved = onSaved,
    )
}
