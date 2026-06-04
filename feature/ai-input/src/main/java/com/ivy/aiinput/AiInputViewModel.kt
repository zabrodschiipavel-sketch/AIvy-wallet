package com.ivy.aiinput

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.model.TransactionType
import com.ivy.ai.domain.ParseTransactionUseCase
import com.ivy.data.model.Account
import com.ivy.data.model.Category
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.Navigation
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import java.time.ZoneOffset
import javax.inject.Inject

@HiltViewModel
class AiInputViewModel @Inject constructor(
    private val parseUseCase: ParseTransactionUseCase,
    private val categoryRepo: CategoryRepository,
    private val accountRepo: AccountRepository,
    private val currencyRepo: CurrencyRepository,
    private val transactionRepo: TransactionRepository,
    private val nav: Navigation,
) : ComposeViewModel<AiInputState, AiInputEvent>() {

    private var inputText by mutableStateOf("")
    private var phase by mutableStateOf(Phase.Input)
    private var loadingContext by mutableStateOf(true)
    private var error by mutableStateOf<AiError?>(null)

    /** Parsed drafts awaiting review (when more than one transaction was recognized). */
    private var reviewDrafts by mutableStateOf<List<EditDraft>>(emptyList())

    /** Cached on first load; refreshed when we navigate back into the screen. */
    private var categories: List<Category> = emptyList()
    private var accounts: List<Account> = emptyList()
    private var defaultCurrency: String = "USD"

    @Composable
    override fun uiState(): AiInputState {
        LaunchedEffect(Unit) { loadContext() }
        return AiInputState(
            inputText = inputText,
            phase = phase,
            loadingContext = loadingContext,
            error = error,
            reviewItems = reviewDrafts.map { it.toReviewItem() }.toImmutableList(),
        )
    }

    private fun loadContext() {
        viewModelScope.launch {
            loadingContext = true
            runCatching {
                categories = categoryRepo.findAll()
                accounts = accountRepo.findAll()
                defaultCurrency = currencyRepo.getBaseCurrency().code
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
            AiInputEvent.SaveAll -> saveAll()
            is AiInputEvent.RemoveItem -> removeItem(event.index)
            is AiInputEvent.EditItem -> editItem(event.index)
            AiInputEvent.BackToInput -> backToInput()
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
                onSuccess = { parsed ->
                    val drafts = parsed.map { it.toEditDraft(accounts = accounts, categories = categories) }
                    when {
                        drafts.isEmpty() -> {
                            error = AiError.NothingFound
                            phase = Phase.Input
                        }
                        // A single transaction goes straight to the full editor (A3 behaviour).
                        drafts.size == 1 -> openEditor(drafts.first())
                        // Multiple transactions are reviewed as a list before saving.
                        else -> {
                            reviewDrafts = drafts
                            phase = Phase.Review
                        }
                    }
                },
                onFailure = {
                    error = it.toAiError()
                    phase = Phase.Input
                },
            )
        }
    }

    /**
     * Hands a single draft to the full edit-transaction screen pre-filled, so the user can
     * review/correct every field (and pick a destination account for transfers) before saving.
     */
    private fun openEditor(draft: EditDraft) {
        nav.navigateTo(
            EditTransactionScreen(
                initialTransactionId = null,
                type = draft.type,
                accountId = draft.accountId,
                categoryId = draft.categoryId,
                amount = draft.amount,
                title = draft.title,
                description = draft.description,
                dateTime = draft.dateTime,
            )
        )
    }

    private fun saveAll() {
        val drafts = reviewDrafts
        if (drafts.isEmpty()) return
        phase = Phase.Saving
        viewModelScope.launch {
            val transactions = drafts.mapNotNull { it.toDomainTransaction(accounts) }
            if (transactions.isEmpty()) {
                error = AiError.Failed("Не удалось собрать ни одной транзакции для сохранения.")
                phase = Phase.Review
                return@launch
            }
            runCatching { transactionRepo.saveMany(transactions) }
                .onSuccess {
                    reset()
                    nav.back()
                }
                .onFailure {
                    error = AiError.Failed(it.message ?: "Не удалось сохранить транзакции.")
                    phase = Phase.Review
                }
        }
    }

    private fun removeItem(index: Int) {
        reviewDrafts = reviewDrafts.filterIndexed { i, _ -> i != index }
        if (reviewDrafts.isEmpty()) {
            phase = Phase.Input
        }
    }

    /** Pull one transaction out of the batch and open it in the full editor for fine edits. */
    private fun editItem(index: Int) {
        val draft = reviewDrafts.getOrNull(index) ?: return
        reviewDrafts = reviewDrafts.filterIndexed { i, _ -> i != index }
        if (reviewDrafts.isEmpty()) {
            phase = Phase.Input
        }
        openEditor(draft)
    }

    private fun backToInput() {
        reviewDrafts = emptyList()
        error = null
        phase = Phase.Input
    }

    private fun reset() {
        inputText = ""
        error = null
        reviewDrafts = emptyList()
        phase = Phase.Input
    }

    private fun EditDraft.toReviewItem(): AiReviewItem {
        val acc = accounts.firstOrNull { it.id.value == accountId }
        val cat = categories.firstOrNull { it.id.value == categoryId }
        val currency = acc?.asset?.code ?: defaultCurrency
        return AiReviewItem(
            amount = amount?.let { "%.2f %s".format(it, currency) } ?: "—",
            typeLabel = when (type) {
                TransactionType.INCOME -> "Доход"
                TransactionType.TRANSFER -> "Перевод (сохранится как расход)"
                else -> "Расход"
            },
            account = acc?.name?.value,
            category = cat?.name?.value,
            date = dateTime?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString(),
            description = description,
        )
    }
}
