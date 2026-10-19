package com.ivy.data.backup

import com.ivy.base.legacy.Theme
import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.BudgetEntity
import com.ivy.data.db.entity.CategoryEntity
import com.ivy.data.db.entity.ExchangeRateEntity
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.PlannedPaymentRuleEntity
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.LoanType
import java.time.Instant
import java.util.UUID

/** One valid row for every grouped import writer, with connected identities. */
internal fun completeRestorePayload(): IvyWalletCompleteData {
    val date = Instant.parse("2024-01-02T03:04:05Z")
    val account = AccountEntity(name = "Incoming account", currency = "USD", color = 0)
    val category = CategoryEntity(name = "Incoming category", color = 0)
    val loan = LoanEntity(
        name = "Incoming loan",
        amount = 10.0,
        type = LoanType.BORROW,
        accountId = account.id
    )
    val record = LoanRecordEntity(
        loanId = loan.id,
        amount = 1.0,
        dateTime = date,
        accountId = account.id
    )
    val rule = PlannedPaymentRuleEntity(
        startDate = date,
        intervalN = null,
        intervalType = null,
        oneTime = true,
        type = TransactionType.EXPENSE,
        accountId = account.id,
        amount = 1.0,
        categoryId = category.id
    )
    val transaction = TransactionEntity(
        accountId = account.id,
        type = TransactionType.EXPENSE,
        amount = 1.0,
        dateTime = date,
        categoryId = category.id,
        recurringRuleId = rule.id,
        loanId = loan.id,
        loanRecordId = record.id
    )
    val tag = TagEntity(
        id = UUID.randomUUID(), name = "Incoming tag", description = null,
        color = 0, icon = null, orderNum = 0.0, dateTime = date,
        isDeleted = false, lastSyncedTime = Instant.EPOCH
    )
    return IvyWalletCompleteData(
        formatVersion = 2,
        accounts = listOf(account), categories = listOf(category),
        loans = listOf(loan), loanRecords = listOf(record),
        plannedPaymentRules = listOf(rule), transactions = listOf(transaction),
        budgets = listOf(
            BudgetEntity(
                name = "Incoming budget",
                amount = 10.0,
                categoryIdsSerialized = null,
                accountIdsSerialized = null,
                orderId = 0.0
            )
        ),
        settings = listOf(
            SettingsEntity(
                theme = Theme.AUTO,
                currency = "USD",
                bufferAmount = 0.0,
                name = "Incoming settings"
            )
        ),
        tags = listOf(tag),
        tagAssociations = listOf(
            TagAssociationEntity(
                tagId = tag.id,
                associatedId = transaction.id,
                lastSyncedTime = Instant.EPOCH,
                isDeleted = false
            )
        ),
        manualExchangeRates = listOf(ExchangeRateEntity("USD", "EUR", 0.9, true)),
        sharedPrefs = HashMap(RestorePreferenceKeys.associateWith { "true" }),
    )
}
