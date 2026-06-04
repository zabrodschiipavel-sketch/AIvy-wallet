package com.ivy.aiinput

import com.ivy.ai.data.GeminiException
import com.ivy.ai.domain.ParsedTransaction
import com.ivy.ai.domain.TransactionTypeGuess
import com.ivy.base.model.TransactionType
import com.ivy.data.model.Account
import com.ivy.data.model.Category
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The fields needed to pre-fill the edit-transaction screen, resolved from a [ParsedTransaction]
 * against the user's real accounts/categories. Kept as a plain value so the mapping can be
 * unit-tested without touching navigation.
 */
internal data class EditDraft(
    val type: TransactionType,
    val accountId: UUID?,
    val categoryId: UUID?,
    val amount: Double?,
    val title: String?,
    val description: String?,
    val dateTime: Instant?,
)

/**
 * Resolves a parsed draft into [EditDraft]:
 * - maps the guessed type (defaulting to EXPENSE),
 * - matches account/category by name (case-insensitive) against the real ones, unknown → null,
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

    val accountId: UUID? = account
        ?.let { name -> accounts.firstOrNull { it.name.value.equals(name, ignoreCase = true) } }
        ?.id?.value

    val categoryId: UUID? = if (type == TransactionType.TRANSFER) {
        null
    } else {
        category
            ?.let { name -> categories.firstOrNull { it.name.value.equals(name, ignoreCase = true) } }
            ?.id?.value
    }

    val dateTime: Instant? = date
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

    return EditDraft(
        type = type,
        accountId = accountId,
        categoryId = categoryId,
        amount = amount?.takeIf { it > 0.0 },
        title = description,
        description = description,
        dateTime = dateTime,
    )
}

/**
 * Builds a persistable domain [Transaction] from a resolved [EditDraft] for the bulk "save all"
 * path. Returns null when the draft can't form a valid transaction (no positive amount, or the
 * user has no accounts). Transfers fall back to an expense, since the destination account isn't
 * inferred from free-form text — those are better handled one-by-one in the editor.
 *
 * [now] is injectable for deterministic tests; [newId] likewise.
 */
internal fun EditDraft.toDomainTransaction(
    accounts: List<Account>,
    now: Instant = Instant.now(),
    newId: () -> UUID = UUID::randomUUID,
): Transaction? {
    val account = accounts.firstOrNull { it.id.value == accountId }
        ?: accounts.firstOrNull()
        ?: return null
    val positiveAmount = amount?.let { PositiveDouble.from(it).getOrNull() } ?: return null

    val value = PositiveValue(amount = positiveAmount, asset = account.asset)
    val resolvedTitle = title?.let { NotBlankTrimmedString.from(it).getOrNull() }
    val resolvedDescription = description?.let { NotBlankTrimmedString.from(it).getOrNull() }
    val resolvedCategory = categoryId?.let { CategoryId(it) }
    val time = dateTime ?: now
    val metadata = TransactionMetadata(
        recurringRuleId = null,
        paidForDateTime = null,
        loanId = null,
        loanRecordId = null,
    )

    return when (type) {
        TransactionType.INCOME -> Income(
            id = TransactionId(newId()),
            title = resolvedTitle,
            description = resolvedDescription,
            category = resolvedCategory,
            time = time,
            settled = true,
            metadata = metadata,
            tags = emptyList(),
            value = value,
            account = account.id,
        )
        // EXPENSE and TRANSFER (no destination from text) both persist as an expense here.
        else -> Expense(
            id = TransactionId(newId()),
            title = resolvedTitle,
            description = resolvedDescription,
            category = resolvedCategory,
            time = time,
            settled = true,
            metadata = metadata,
            tags = emptyList(),
            value = value,
            account = account.id,
        )
    }
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
