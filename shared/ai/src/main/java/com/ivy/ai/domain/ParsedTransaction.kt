package com.ivy.ai.domain

/**
 * Result of parsing a free-form text description into a transaction draft.
 * All fields are nullable — when the model can't determine a value, we leave it
 * empty so the user fills it in manually on the EditTransaction screen.
 *
 * This is a *draft*, never persisted directly. The user always confirms before save.
 */
data class ParsedTransaction(
    val amount: Double?,
    val currency: String?,
    /** ISO-8601 local date, e.g. "2026-05-23". Null if the model didn't infer a date. */
    val date: String?,
    /** One of the category names passed in to the parser, or null. */
    val category: String?,
    /** One of the account names passed in to the parser, or null. */
    val account: String?,
    val description: String?,
    val transactionType: TransactionTypeGuess?,
)

enum class TransactionTypeGuess { EXPENSE, INCOME, TRANSFER }
