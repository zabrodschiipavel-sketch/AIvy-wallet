package com.ivy.data.loan

import androidx.room.withTransaction
import com.ivy.base.model.LoanSaveResult
import com.ivy.base.model.LoanRecordType
import com.ivy.base.model.TransactionType
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.repository.RepositoryMemoFactory
import com.ivy.data.model.LoanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class LoanTransactionEditCommand @Inject constructor(
    private val database: IvyRoomDatabase,
    private val memoFactory: RepositoryMemoFactory,
    private val observer: DataObserver,
    private val dispatchers: DispatchersProvider,
) {
    suspend fun snapshot(id: UUID): TransactionEntity? = withContext(dispatchers.io) {
        database.transactionDao.findById(id)
    }

    // Only dependent database writes belong in the callback. Publish after releasing Room's lock.
    suspend fun save(
        expected: TransactionEntity,
        updated: TransactionEntity,
        writeAssociatedData: suspend () -> Unit,
    ): LoanSaveResult {
        try {
            withContext(dispatchers.io) {
                database.withTransaction {
                    validate(expected, updated)
                    writeAssociatedData()
                    validateAssociatedWrites(updated)
                    database.writeTransactionDao.save(updated.copy(isSynced = false))
                }
            }
        } finally {
            withContext(NonCancellable + dispatchers.io) { memoFactory.invalidateAll() }
        }
        return withContext(NonCancellable + dispatchers.io) {
            try {
                observer.post(DataWriteEvent.AllDataChange)
                LoanSaveResult.Saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LoanSaveResult.RefreshFailed
            }
        }
    }

    private suspend fun validate(expected: TransactionEntity, updated: TransactionEntity) {
        require(expected.id == updated.id && !expected.isDeleted && !updated.isDeleted)
        require(database.transactionDao.findById(expected.id) == expected) {
            "Transaction changed since this editor was opened"
        }
        val loanId = requireNotNull(expected.loanId)
        require(updated.loanId == loanId && updated.loanRecordId == expected.loanRecordId)
        require(updated.amount.isFinite() && updated.amount > 0)
        require(updated.type != TransactionType.TRANSFER && updated.toAccountId == null)
        require(updated.dateTime != null && updated.dueDate == null)
        val loan = requireNotNull(database.loanDao.findById(loanId))
        require(!loan.isDeleted)
        val ownerAccount = if (expected.loanRecordId != null) {
            val record = requireNotNull(database.loanRecordDao.findById(expected.loanRecordId))
            require(!record.isDeleted && record.loanId == loanId)
            require(updated.type == expected.type) { "Cannot change repayment direction here" }
            record.accountId
        } else {
            loan.accountId
        }
        require(ownerAccount == expected.accountId) { "Loan and transaction accounts disagree" }
        val account = requireNotNull(database.accountDao.findById(updated.accountId))
        require(!account.isDeleted && (!account.archived || updated.accountId == expected.accountId))
    }

    private suspend fun validateAssociatedWrites(updated: TransactionEntity) {
        val loan = requireNotNull(database.loanDao.findById(requireNotNull(updated.loanId)))
        require(!loan.isDeleted)
        if (updated.loanRecordId == null) {
            require(loan.accountId == updated.accountId && loan.amount == updated.amount)
            val type = if (updated.type == TransactionType.INCOME) LoanType.BORROW else LoanType.LEND
            require(loan.type == type)
        } else {
            val record = requireNotNull(database.loanRecordDao.findById(updated.loanRecordId))
            require(!record.isDeleted && record.loanId == loan.id)
            require(record.accountId == updated.accountId && record.amount == updated.amount)
            require(record.dateTime == updated.dateTime)
            require(record.convertedAmount == null || record.convertedAmount.isFinite())
            val incoming = (loan.type == LoanType.BORROW) == (record.loanRecordType == LoanRecordType.INCREASE)
            require(updated.type == if (incoming) TransactionType.INCOME else TransactionType.EXPENSE)
        }
    }
}
