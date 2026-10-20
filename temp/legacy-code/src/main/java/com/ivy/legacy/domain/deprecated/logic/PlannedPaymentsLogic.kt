package com.ivy.wallet.domain.deprecated.logic

import androidx.room.withTransaction
import com.ivy.base.legacy.Transaction
import com.ivy.base.model.TransactionType
import com.ivy.base.time.TimeProvider
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.db.dao.read.PlannedPaymentRuleDao
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.IntervalType
import com.ivy.data.model.TransactionId
import com.ivy.data.model.Transfer
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.data.temp.migration.settleNow
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.PlannedPaymentRule
import com.ivy.legacy.datamodel.temp.toDomain
import com.ivy.legacy.datamodel.temp.toLegacyDomain
import com.ivy.legacy.utils.ioThread
import com.ivy.wallet.domain.deprecated.logic.currency.ExchangeRatesLogic
import com.ivy.wallet.domain.deprecated.logic.currency.sumByDoublePlannedInBaseCurrency
import javax.inject.Inject

@Deprecated("Migrate to FP Style")
class PlannedPaymentsLogic @Inject constructor(
    private val plannedPaymentRuleDao: PlannedPaymentRuleDao,
    private val transactionDao: TransactionDao,
    private val settingsDao: SettingsDao,
    private val exchangeRatesLogic: ExchangeRatesLogic,
    private val accountDao: AccountDao,
    private val transactionMapper: TransactionMapper,
    private val plannedPaymentRuleWriter: WritePlannedPaymentRuleDao,
    private val transactionRepository: TransactionRepository,
    private val timeProvider: TimeProvider,
    private val database: IvyRoomDatabase,
) {
    companion object {
        private const val AVG_DAYS_IN_MONTH = 30.436875
    }

    suspend fun plannedPaymentsAmountFor(range: com.ivy.legacy.data.model.FromToTimeRange): Double {
        val baseCurrency = settingsDao.findFirst().currency
        val accounts = accountDao.findAll()

        return transactionDao.findAllDueToBetween(
            startDate = range.from(),
            endDate = range.to()
        ).sumOf {
            val amount = exchangeRatesLogic.amountBaseCurrency(
                transaction = it.toLegacyDomain(),
                baseCurrency = baseCurrency,
                accounts = accounts.map { it.toLegacyDomain() }
            )

            when (it.type) {
                TransactionType.INCOME -> amount
                TransactionType.EXPENSE -> -amount
                TransactionType.TRANSFER -> 0.0
            }
        }
    }

    suspend fun oneTime(): List<PlannedPaymentRule> {
        return plannedPaymentRuleDao.findAllByOneTime(oneTime = true).map { it.toLegacyDomain() }
    }

    suspend fun oneTimeIncome(): Double {
        return oneTime()
            .filter { it.type == TransactionType.INCOME }
            .sumByDoublePlannedInBaseCurrency(
                exchangeRatesLogic = exchangeRatesLogic,
                settingsDao = settingsDao,
                accountDao = accountDao
            )
    }

    suspend fun oneTimeExpenses(): Double {
        return oneTime()
            .filter { it.type == TransactionType.EXPENSE }
            .sumByDoublePlannedInBaseCurrency(
                exchangeRatesLogic = exchangeRatesLogic,
                settingsDao = settingsDao,
                accountDao = accountDao
            )
    }

    suspend fun recurring(): List<PlannedPaymentRule> =
        plannedPaymentRuleDao.findAllByOneTime(oneTime = false).map { it.toLegacyDomain() }

    suspend fun recurringIncome(): Double {
        return recurring()
            .filter { it.type == TransactionType.INCOME }
            .sumByDoubleRecurringForMonthInBaseCurrency()
    }

    suspend fun recurringExpenses(): Double {
        return recurring()
            .filter { it.type == TransactionType.EXPENSE }
            .sumByDoubleRecurringForMonthInBaseCurrency()
    }

    private suspend fun Iterable<PlannedPaymentRule>.sumByDoubleRecurringForMonthInBaseCurrency(): Double {
        val accounts = accountDao.findAll()
        val baseCurrency = settingsDao.findFirst().currency

        return sumOf {
            amountForMonthInBaseCurrency(
                plannedPayment = it,
                baseCurrency = baseCurrency,
                accounts = accounts.map { it.toLegacyDomain() }
            )
        }
    }

    private suspend fun amountForMonthInBaseCurrency(
        plannedPayment: PlannedPaymentRule,
        baseCurrency: String,
        accounts: List<Account>
    ): Double {
        val amountBaseCurrency = exchangeRatesLogic.amountBaseCurrency(
            plannedPayment = plannedPayment,
            baseCurrency = baseCurrency,
            accounts = accounts,
        )

        if (plannedPayment.oneTime) {
            return amountBaseCurrency
        }

        val intervalN = plannedPayment.intervalN ?: return amountBaseCurrency
        if (intervalN <= 0) {
            return amountBaseCurrency
        }

        return when (plannedPayment.intervalType) {
            IntervalType.DAY -> {
                val monthDiff = 1 / AVG_DAYS_IN_MONTH // 0.03%

                (amountBaseCurrency / monthDiff) / intervalN
            }

            IntervalType.WEEK -> {
                val monthDiff = 7 / AVG_DAYS_IN_MONTH // 0.22%

                (amountBaseCurrency / monthDiff) / intervalN
            }

            IntervalType.MONTH -> {
                amountBaseCurrency / intervalN
            }

            IntervalType.YEAR -> {
                amountBaseCurrency / (12 * intervalN)
            }

            null -> amountBaseCurrency
        }
    }

    @Deprecated("Uses legacy Transaction")
    suspend fun payOrGetLegacy(
        transaction: Transaction,
        syncTransaction: Boolean = true,
        skipTransaction: Boolean = false,
        onUpdateUI: suspend (paidTransaction: Transaction) -> Unit
    ) = payOrGetLegacy(listOf(transaction), syncTransaction, skipTransaction) {
        onUpdateUI(it.single())
    }

    suspend fun payOrGet(
        transaction: com.ivy.data.model.Transaction,
        syncTransaction: Boolean = true,
        skipTransaction: Boolean = false,
        onUpdateUI: suspend (paidTransaction: com.ivy.data.model.Transaction) -> Unit
    ) = payOrGet(listOf(transaction), syncTransaction, skipTransaction) {
        onUpdateUI(it.single())
    }

    suspend fun payOrGet(
        transactions: List<com.ivy.data.model.Transaction>,
        syncTransaction: Boolean = true,
        skipTransaction: Boolean = false,
        onUpdateUI: suspend (paidTransactions: List<com.ivy.data.model.Transaction>) -> Unit
    ) {
        val paidTransactions = ioThread {
            database.withTransaction {
                transactions.filterNot { it.settled }.distinctBy { it.id }.mapNotNull { selected ->
                    // Missing rows can be generated pending occurrences, not yet persisted.
                    val stored = transactionDao.findById(selected.id.value)
                    val pending = if (stored == null) {
                        selected
                    } else {
                        if (stored.dateTime != null || stored.isDeleted) return@mapNotNull null
                        checkNotNull(transactionRepository.findById(selected.id)) {
                            "Cannot read planned transaction ${selected.id.value}"
                        }
                    }
                    if (pending.settled) return@mapNotNull null
                    val metadata = pending.metadata.copy(paidForDateTime = pending.time)
                    val paid = when (val settled = pending.settleNow()) {
                        is Income -> settled.copy(metadata = metadata)
                        is Expense -> settled.copy(metadata = metadata)
                        is Transfer -> settled.copy(metadata = metadata)
                    }
                    if (skipTransaction) {
                        transactionRepository.deleteById(paid.id)
                    } else {
                        transactionRepository.save(paid)
                    }
                    deleteOneTimeRule(paid.metadata.recurringRuleId)
                    paid
                }
            }
        }
        if (paidTransactions.isNotEmpty()) onUpdateUI(paidTransactions)
    }

    @Deprecated("Uses legacy Transaction")
    suspend fun payOrGetLegacy(
        transactions: List<Transaction>,
        syncTransaction: Boolean = true,
        skipTransaction: Boolean = false,
        onUpdateUI: suspend (paidTransactions: List<Transaction>) -> Unit
    ) {
        val paidTransactions = ioThread {
            database.withTransaction {
                transactions.filter { it.dueDate != null && it.dateTime == null }
                    .distinctBy { it.id }.mapNotNull { selected ->
                        val stored = transactionDao.findById(selected.id)
                        if (stored != null && (stored.dateTime != null || stored.isDeleted)) {
                            return@mapNotNull null
                        }
                        val pending = stored?.toLegacyDomain() ?: selected
                        if (pending.dueDate == null || pending.dateTime != null) return@mapNotNull null
                        val paid = pending.copy(
                            paidFor = pending.dueDate,
                            dueDate = null,
                            dateTime = timeProvider.utcNow(),
                            isSynced = false,
                        )
                        if (skipTransaction) {
                            transactionRepository.deleteById(TransactionId(paid.id))
                        } else {
                            val domain = checkNotNull(paid.toDomain(transactionMapper)) {
                                "Cannot settle planned transaction ${paid.id}"
                            }
                            transactionRepository.save(domain)
                        }
                        deleteOneTimeRule(paid.recurringRuleId)
                        paid
                    }
            }
        }
        if (paidTransactions.isNotEmpty()) onUpdateUI(paidTransactions)
    }

    private suspend fun deleteOneTimeRule(id: java.util.UUID?) {
        val rule = id?.let { plannedPaymentRuleDao.findById(it) }
        if (rule?.oneTime == true) plannedPaymentRuleWriter.deleteById(rule.id)
    }
}
