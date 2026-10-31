package com.ivy.data.account

import androidx.room.withTransaction
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.model.Account
import com.ivy.data.model.Transaction
import com.ivy.data.model.getFromAccount
import com.ivy.data.repository.RepositoryMemoFactory
import com.ivy.data.repository.mapper.AccountMapper
import com.ivy.data.repository.mapper.TransactionMapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Writes only this account and its optional balance adjustment; never publishes memo state. */
class AccountBalanceWriteCommand @Inject constructor(
    private val database: IvyRoomDatabase,
    private val accountMapper: AccountMapper,
    private val transactionMapper: TransactionMapper,
    private val memoFactory: RepositoryMemoFactory,
    private val observer: DataObserver,
    private val dispatchers: DispatchersProvider,
) {
    suspend fun save(
        account: suspend () -> Account,
        adjustment: suspend (Account) -> Transaction?,
    ) {
        var committed = false
        var bodyCompleted = false
        var accountRow: AccountEntity? = null
        var adjustmentRow: TransactionEntity? = null
        try {
            withContext(dispatchers.io) {
                try {
                    database.withTransaction {
                        val value = account()
                        require(value.orderNum.isFinite()) { "Invalid account order" }
                        accountRow = with(accountMapper) { value.toEntity() }
                        val transaction = adjustment(value)
                        require(transaction == null || transaction.getFromAccount() == value.id) {
                            "Adjustment belongs to another account"
                        }
                        adjustmentRow = transaction?.let { with(transactionMapper) { it.toEntity() } }
                        database.writeAccountDao.save(checkNotNull(accountRow))
                        adjustmentRow?.let { database.writeTransactionDao.save(it) }
                        bodyCompleted = true
                    }
                    committed = true
                } finally {
                    // Also clear on rollback or cancellation at Room's return boundary.
                    memoFactory.invalidateAll()
                }
            }
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                // Cancellation may be delivered after commit but before withTransaction returns.
                if (!committed && bodyCompleted) {
                    val row = checkNotNull(accountRow)
                    val adjustment = adjustmentRow
                    committed = database.accountDao.findById(row.id) == row &&
                        (adjustment == null || database.transactionDao.findById(adjustment.id) == adjustment)
                }
                memoFactory.invalidateAll()
                // Room's transaction lock has been released before notifying reentrant observers.
                if (committed) observer.post(DataWriteEvent.AllDataChange)
            }
        }
    }
}
