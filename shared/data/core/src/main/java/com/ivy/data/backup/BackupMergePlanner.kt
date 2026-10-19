package com.ivy.data.backup

import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.model.primitive.AssetCode
import java.time.Instant

internal data class BackupMergePlan(
    val additions: IvyWalletCompleteData,
    val skippedTransactions: Int,
    val preferences: Map<String, Boolean>,
    val warnings: List<String>,
)

/** Pure validation and identity-based merge. Names and user-authored text are never rewritten. */
internal object BackupMergePlanner {
    fun plan(incoming: IvyWalletCompleteData, current: IvyWalletCompleteData): BackupMergePlan {
        require(incoming.formatVersion in 1..2) { "Unsupported backup version ${incoming.formatVersion}" }
        val data = normalize(incoming)
        val existing = normalize(current)
        val warnings = mutableListOf<String>()
        val preferences = incoming.sharedPrefs.filterKeys { it in RestorePreferenceKeys }.mapValues { (key, value) ->
            value.toBooleanStrictOrNull() ?: error("Invalid boolean preference: $key")
        }

        val accounts = additions("account", data.accounts, existing.accounts) { it.id }
        val categories = additions("category", data.categories, existing.categories) { it.id }
        val budgets = additions("budget", data.budgets, existing.budgets) { it.id }
        val loans = additions("loan", data.loans, existing.loans) { it.id }
        val loanRecords = additions("loan record", data.loanRecords, existing.loanRecords) { it.id }
        val rules = additions("planned payment", data.plannedPaymentRules, existing.plannedPaymentRules) { it.id }
        val transactions = additions("transaction", data.transactions, existing.transactions) { it.id }
        val tags = additions("tag", data.tags, existing.tags) { it.id }
        val associations = additions("tag association", data.tagAssociations, existing.tagAssociations) {
            it.tagId to it.associatedId
        }
        val rates = additions("manual exchange rate", data.manualExchangeRates, existing.manualExchangeRates) {
            it.baseCurrency to it.currency
        }
        val settings = mergeSettings(data.settings, existing.settings)

        validateAccountsAndCategories(data)
        validateTransactions(data, existing)
        validatePlannedPayments(data, existing)
        validateLoans(data, existing)
        validateOtherRecords(data, existing)

        if (incoming.formatVersion == 1) {
            warnings += "Legacy backup has no manual exchange rates; existing rates were kept."
        }
        if (incoming.transactions.size != data.transactions.size) {
            warnings += "Legacy deleted transactions were skipped."
        }

        return BackupMergePlan(
            additions = IvyWalletCompleteData(
                formatVersion = 2,
                accounts = accounts,
                categories = categories,
                budgets = budgets,
                loans = loans,
                loanRecords = loanRecords,
                plannedPaymentRules = rules,
                transactions = transactions,
                tags = tags,
                tagAssociations = associations,
                settings = settings,
                manualExchangeRates = rates,
            ),
            skippedTransactions = incoming.transactions.size - transactions.size,
            preferences = preferences,
            warnings = warnings,
        )
    }

    private fun validateAccountsAndCategories(data: IvyWalletCompleteData) {
        data.accounts.forEach {
            require(it.name.isNotBlank()) { "Account ${it.id} has an empty name" }
            validCurrency(it.currency ?: error("Account ${it.id} has no currency"))
            require(it.orderNum.isFinite()) { "Invalid account order" }
        }
        data.categories.forEach {
            require(it.name.isNotBlank() && it.orderNum.isFinite()) { "Invalid category ${it.id}" }
        }
    }

    private fun validateTransactions(data: IvyWalletCompleteData, existing: IvyWalletCompleteData) {
        val accountIds = (existing.accounts + data.accounts).map { it.id }.toSet()
        val loanIds = (existing.loans + data.loans).map { it.id }.toSet()
        val recordIds = (existing.loanRecords + data.loanRecords).map { it.id }.toSet()
        data.transactions.forEach {
            positive(it.amount, "transaction ${it.id}")
            require(it.accountId in accountIds) { "Transaction ${it.id} has no source account" }
            require(it.dateTime != null || it.dueDate != null) { "Transaction ${it.id} has no date" }
            if (it.type == TransactionType.TRANSFER) {
                require(it.toAccountId in accountIds && it.toAccountId != it.accountId) {
                    "Transfer ${it.id} has an invalid destination account"
                }
                it.toAmount?.let { amount -> positive(amount, "transfer ${it.id}") }
            }
            require(it.loanId == null || it.loanId in loanIds) { "Transaction ${it.id} has no linked loan" }
            require(it.loanRecordId == null || it.loanRecordId in recordIds) {
                "Transaction ${it.id} has no linked loan record"
            }
        }
    }

    private fun validatePlannedPayments(data: IvyWalletCompleteData, existing: IvyWalletCompleteData) {
        val accountIds = (existing.accounts + data.accounts).map { it.id }.toSet()
        data.plannedPaymentRules.forEach {
            positive(it.amount, "planned payment ${it.id}")
            require(it.accountId in accountIds && it.startDate != null) { "Invalid planned payment ${it.id}" }
            require(it.type != TransactionType.TRANSFER) { "Unsupported planned transfer ${it.id}" }
            require(it.oneTime || (it.intervalN != null && it.intervalN > 0 && it.intervalType != null)) {
                "Invalid recurrence for ${it.id}"
            }
        }
    }

    private fun validateLoans(data: IvyWalletCompleteData, existing: IvyWalletCompleteData) {
        val accountIds = (existing.accounts + data.accounts).map { it.id }.toSet()
        val loanIds = (existing.loans + data.loans).map { it.id }.toSet()
        data.loans.forEach {
            positive(it.amount, "loan ${it.id}")
            require(it.accountId == null || it.accountId in accountIds) { "Loan ${it.id} has no account" }
        }
        data.loanRecords.forEach {
            positive(it.amount, "loan record ${it.id}")
            it.convertedAmount?.let { amount -> positive(amount, "loan record ${it.id}") }
            require(it.loanId in loanIds) { "Loan record ${it.id} has no loan" }
            require(it.accountId == null || it.accountId in accountIds) { "Loan record ${it.id} has no account" }
        }
    }

    private fun validateOtherRecords(data: IvyWalletCompleteData, existing: IvyWalletCompleteData) {
        val tagIds = (existing.tags + data.tags).map { it.id }.toSet()
        val transactionIds = (existing.transactions + data.transactions).map { it.id }.toSet()
        data.budgets.forEach { positive(it.amount, "budget ${it.id}") }
        data.tags.forEach { require(it.name.isNotBlank()) { "Tag ${it.id} has an empty name" } }
        data.tagAssociations.forEach {
            require(it.tagId in tagIds && it.associatedId in transactionIds) { "Dangling tag association" }
        }
        data.manualExchangeRates.forEach {
            validCurrency(it.baseCurrency)
            validCurrency(it.currency)
            positive(it.rate, "manual exchange rate")
            require(it.manualOverride) { "Backup exchange rates must be manual overrides" }
        }
        data.settings.forEach {
            validCurrency(it.currency)
            require(it.bufferAmount.isFinite()) { "Invalid settings buffer" }
        }
    }

    private fun normalize(data: IvyWalletCompleteData): IvyWalletCompleteData {
        val baseCurrency = data.settings.singleOrNull()?.currency
        return data.copy(
            accounts = data.accounts.filterNot { it.isDeleted }.map {
                it.copy(currency = it.currency ?: baseCurrency, isSynced = false)
            },
            categories = data.categories.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            budgets = data.budgets.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            loans = data.loans.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            loanRecords = data.loanRecords.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            plannedPaymentRules = data.plannedPaymentRules.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            transactions = data.transactions.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            settings = data.settings.filterNot { it.isDeleted }.map { it.copy(isSynced = false) },
            tags = data.tags.filterNot { it.isDeleted }.map { it.copy(lastSyncedTime = Instant.EPOCH) },
            tagAssociations = data.tagAssociations.filterNot { it.isDeleted }.map {
                it.copy(lastSyncedTime = Instant.EPOCH)
            },
        )
    }

    private fun <T, K> additions(name: String, incoming: List<T>, current: List<T>, id: (T) -> K): List<T> {
        val known = current.associateBy(id).toMutableMap()
        return incoming.filter { value ->
            val key = id(value)
            val previous = known[key]
            require(previous == null || previous == value) { "Conflicting $name ID $key; no data was imported" }
            if (previous == null) {
                known[key] = value
                true
            } else {
                false
            }
        }
    }

    private fun mergeSettings(incoming: List<SettingsEntity>, current: List<SettingsEntity>): List<SettingsEntity> {
        require(incoming.size <= 1 && current.size <= 1) { "Backup or database contains multiple settings records" }
        return when {
            incoming.isEmpty() -> emptyList()
            current.isEmpty() -> incoming
            else -> {
                val previous = current.single()
                require(incoming.single().copy(id = previous.id) == previous) {
                    "Conflicting app settings; no data was imported"
                }
                emptyList()
            }
        }
    }

    private fun positive(amount: Double, description: String) {
        require(amount.isFinite() && amount > 0) { "Invalid amount for $description" }
    }

    private fun validCurrency(code: String) {
        require(AssetCode.from(code).isRight()) { "Invalid currency code" }
    }
}
