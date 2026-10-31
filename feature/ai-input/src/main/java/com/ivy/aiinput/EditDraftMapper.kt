package com.ivy.aiinput

import com.ivy.ai.data.GeminiException
import com.ivy.ai.domain.ParsedTransaction
import com.ivy.ai.domain.TransactionTypeGuess
import com.ivy.base.model.TransactionType
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The fields needed to pre-fill the edit-transaction screen, resolved from a [ParsedTransaction]
 * against the user's real accounts/categories. Kept as a plain value so the mapping can be
 * unit-tested without touching navigation.
 */
internal data class EditDraft(
    val type: TransactionType,
    val account: AccountId?,
    val category: CategoryId?,
    val amount: Double?,
    val title: String?,
    val dateTime: Instant?,
)

/**
 * Resolves a parsed draft into [EditDraft]:
 * - maps the guessed type (defaulting to EXPENSE),
 * - matches an unambiguous active account and category by name (case-insensitive), unknown → null,
 * - drops the category for transfers (the destination account is chosen in the editor),
 * - keeps the amount only when positive,
 * - parses the ISO date, ignoring an unparseable one.
 */
internal fun ParsedTransaction.toEditDraft(
    accounts: List<Account>,
    categories: List<Category>,
): EditDraft {
    val type = when (transactionType) {
        TransactionTypeGuess.INCOME -> TransactionType.INCOME
        TransactionTypeGuess.TRANSFER -> TransactionType.TRANSFER
        else -> TransactionType.EXPENSE
    }

    val accountId: AccountId? = account
        ?.let { name ->
            accounts.singleOrNull { !it.archived && it.name.value.equals(name, ignoreCase = true) }
        }
        ?.id

    val categoryId: CategoryId? = if (type == TransactionType.TRANSFER) {
        null
    } else {
        category
            ?.let { name -> categories.firstOrNull { it.name.value.equals(name, ignoreCase = true) } }
            ?.id
    }

    val dateTime: Instant? = date
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

    return EditDraft(
        type = type,
        account = accountId,
        category = categoryId,
        amount = amount?.takeIf { it.isFinite() && it > 0.0 },
        title = description,
        dateTime = dateTime,
    )
}

/** Maps a parse/save failure to a user-facing [AiError]. */
internal fun Throwable.toAiError(): AiError = when {
    this is GeminiException -> when (reason) {
        GeminiException.Reason.MissingApiKey -> AiError.MissingApiKey
        GeminiException.Reason.Network -> AiError.Network
        GeminiException.Reason.EmptyResponse ->
            AiError.Failed("Gemini не вернул результат. Попробуйте переформулировать.")
        GeminiException.Reason.Unknown -> AiError.Failed(message ?: "Неизвестная ошибка.")
    }
    else -> AiError.Failed(message ?: "Не удалось распознать транзакцию.")
}
