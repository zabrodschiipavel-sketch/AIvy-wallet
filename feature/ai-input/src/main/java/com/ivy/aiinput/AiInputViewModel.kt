package com.ivy.aiinput

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.ai.domain.ParseTransactionUseCase
import com.ivy.ai.domain.ParsedTransaction
import com.ivy.data.model.Account
import com.ivy.data.model.Category
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiInputViewModel @Inject constructor(
    private val parseUseCase: ParseTransactionUseCase,
    private val categoryRepo: CategoryRepository,
    private val accountRepo: AccountRepository,
    private val nav: Navigation,
) : ComposeViewModel<AiInputState, AiInputEvent>() {

    private var inputText by mutableStateOf("")
    private var phase by mutableStateOf(Phase.Input)
    private var loadingContext by mutableStateOf(true)
    private var error by mutableStateOf<AiError?>(null)

    /** Cached on first load; refreshed when we navigate back into the screen. */
    private var categories: List<Category> = emptyList()
    private var accounts: List<Account> = emptyList()

    @Composable
    override fun uiState(): AiInputState {
        LaunchedEffect(Unit) { loadContext() }
        return AiInputState(
            inputText = inputText,
            phase = phase,
            loadingContext = loadingContext,
            error = error,
        )
    }

    private fun loadContext() {
        viewModelScope.launch {
            loadingContext = true
            runCatching {
                categories = categoryRepo.findAll()
                accounts = accountRepo.findAll().filterNot { it.archived }
            }
            loadingContext = false
        }
    }

    override fun onEvent(event: AiInputEvent) {
        when (event) {
            is AiInputEvent.InputChanged -> {
                inputText = event.value
                error = null
            }
            AiInputEvent.Parse -> parse()
        }
    }

    private fun parse() {
        val text = inputText.trim()
        if (text.isEmpty()) return
        if (accounts.isEmpty()) {
            // Nothing to attach the transaction to.
            error = AiError.NoAccounts
            phase = Phase.Input
            return
        }
        error = null
        phase = Phase.Parsing
        viewModelScope.launch {
            val result = parseUseCase(
                userText = text,
                categoryNames = categories.map { it.name.value },
                accountNames = accounts.map { it.name.value },
            )
            result.fold(
                onSuccess = { openEditor(it) },
                onFailure = {
                    error = it.toAiError()
                    phase = Phase.Input
                },
            )
        }
    }

    /**
     * Hands the parsed draft to the full edit-transaction screen pre-filled, so the user
     * can review/correct every field (and pick a destination account for transfers)
     * before saving. The edit screen *is* the editable preview.
     */
    private fun openEditor(parsed: ParsedTransaction) {
        val draft = parsed.toEditDraft(accounts = accounts, categories = categories)

        nav.navigateTo(
            EditTransactionScreen(
                initialTransactionId = null,
                type = draft.type,
                accountId = draft.accountId,
                categoryId = draft.categoryId,
                amount = draft.amount,
                title = draft.title,
                description = null,
                dateTime = draft.dateTime,
            )
        )

        // Leave the screen clean for when the user navigates back to it.
        reset()
    }

    private fun reset() {
        inputText = ""
        error = null
        phase = Phase.Input
    }
}
