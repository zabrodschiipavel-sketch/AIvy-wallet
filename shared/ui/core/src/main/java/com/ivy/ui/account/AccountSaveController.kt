package com.ivy.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ivy.base.model.AccountSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class AccountSaveController(
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val onSaved: (AccountSaveResult) -> Unit,
    private val singleSave: Boolean = true,
) {
    var isSaving by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var active = true
    private var committed = false

    fun submit(operation: suspend () -> AccountSaveResult) {
        if (!active || committed || !scope.isActive || !isCurrent() || isSaving) return
        // Set before launch: a second tap may arrive before the coroutine starts.
        isSaving = true
        failed = false
        scope.launch {
            val result = try {
                operation().also { coroutineContext.ensureActive() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (active && isCurrent()) failed = true
                return@launch
            } finally {
                if (active) isSaving = false
            }
            if (active && isCurrent()) {
                committed = singleSave
                onSaved(result)
            }
        }
    }

    fun dispose() {
        active = false
        scope.cancel()
    }
}
