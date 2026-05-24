package com.ivy.aiinput

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.ai.domain.ParseTransactionUseCase
import com.ivy.ai.domain.ParsedTransaction
import com.ivy.ai.domain.TransactionTypeGuess
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AiInputViewModel @Inject constructor(
    private val parseUseCase: ParseTransactionUseCase,
    private val categoryRepo: CategoryRepository,
    private val accountRepo: AccountRepository,
    private val currencyRepo: CurrencyRepository,
    private val transactionRepo: TransactionRepository,
) : ComposeViewModel<AiInputState, AiInputEvent>() {

    private var inputText by mutableStateOf("")
    private var phase by mutableStateOf(Phase.Input)
    private var loadingContext by mutableStateOf(true)
    private var parsed by mutableStateOf<ParsedTransaction?>(null)
    private var defaultCurrency by mutableStateOf("USD")

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
            parsed = parsed,
            defaultCurrency = defaultCurrency,
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
                if (phase == Phase.Preview) phase = Phase.Input
            }
            AiInputEvent.Parse -> parse()
            AiInputEvent.Save -> save()
            AiInputEvent.Reset -> reset()
            is AiInputEvent.Error -> Unit // surfaced via state if needed
        }
    }

    private fun parse() {
        val text = inputText.trim()
        if (text.isEmpty()) return
        if (accounts.isEmpty()) {
            // Nothing to attach the transaction to. Surface as a parse failure.
            phase = Phase.Input
            return
        }
        phase = Phase.Parsing
        viewModelScope.launch {
            val result = parseUseCase(
                userText = text,
                categoryNames = categories.map { it.name.value },
                accountNames = accounts.map { it.name.value },
            )
            result.fold(
                onSuccess = {
                    parsed = it
                    phase = Phase.Preview
                },
                onFailure = {
                    parsed = null
                    phase = Phase.Input
                },
            )
        }
    }

    private fun save() {
        val draft = parsed ?: return
        val amount = draft.amount?.takeIf { it > 0.0 } ?: return
        val type = draft.transactionType ?: TransactionTypeGuess.EXPENSE

        // Transfer not yet supported in the AI flow; fall back to Expense.
        val effectiveType = if (type == TransactionTypeGuess.TRANSFER) {
            TransactionTypeGuess.EXPENSE
        } else type

        // Resolve account: prefer model's pick, fall back to first available.
        val account: Account = draft.account
            ?.let { name -> accounts.firstOrNull { it.name.value.equals(name, ignoreCase = true) } }
            ?: accounts.first()

        // Resolve category — null is fine (uncategorized).
        val category: Category? = draft.category
            ?.let { name -> categories.firstOrNull { it.name.value.equals(name, ignoreCase = true) } }

        val time: Instant = draft.date
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
            ?: Instant.now()

        val description = draft.description?.let { NotBlankTrimmedString.from(it).getOrNull() }
        // No separate title field comes back from the model; use description as title too.
        val title = description

        val assetCode = AssetCode.from(draft.currency ?: defaultCurrency).getOrNull()
            ?: AssetCode.from(defaultCurrency).getOrNull()
            ?: account.asset
        val value = PositiveValue(
            amount = PositiveDouble.from(amount).getOrNull() ?: return,
            asset = assetCode,
        )

        val metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
        )

        val transaction: Transaction = when (effectiveType) {
            TransactionTypeGuess.INCOME -> Income(
                id = TransactionId(UUID.randomUUID()),
                title = title,
                description = description,
                category = category?.id,
                time = time,
                settled = true,
                metadata = metadata,
                tags = emptyList(),
                value = value,
                account = account.id,
            )
            else -> Expense(
                id = TransactionId(UUID.randomUUID()),
                title = title,
                description = description,
                category = category?.id,
                time = time,
                settled = true,
                metadata = metadata,
                tags = emptyList(),
                value = value,
                account = account.id,
            )
        }

        phase = Phase.Saving
        viewModelScope.launch {
            runCatching { transactionRepo.save(transaction) }
                .onSuccess { phase = Phase.Saved }
                .onFailure { phase = Phase.Preview }
        }
    }

    private fun reset() {
        inputText = ""
        parsed = null
        phase = Phase.Input
    }
}

// Resolve helpers for unused IDs to avoid warnings in IDE search:
@Suppress("unused")
private val unusedIds = listOf(AccountId::class, CategoryId::class)
