package com.ivy.data.loan

import androidx.room.withTransaction
import com.ivy.base.model.LoanSaveResult
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.LoanEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.repository.RepositoryMemoFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class LoanWriteCommand @Inject constructor(
    private val database: IvyRoomDatabase,
    private val memoFactory: RepositoryMemoFactory,
    private val observer: DataObserver,
    private val dispatchers: DispatchersProvider,
) {
    // The callback contains only dependent database writes, never UI refreshes.
    suspend fun saveLoan(
        loan: LoanEntity,
        creating: Boolean,
        writeAssociatedData: suspend () -> Unit,
    ): LoanSaveResult = write {
        require(loan.name.isNotBlank() && loan.amount.isFinite() && loan.amount > 0)
        require(!loan.isDeleted)
        val previous = database.loanDao.findById(loan.id)
        require(if (creating) previous == null else previous != null && !previous.isDeleted)
        validateAccount(loan.accountId)
        database.writeLoanDao.save(loan.copy(isSynced = false))
        writeAssociatedData()
    }

    suspend fun saveRecord(
        record: LoanRecordEntity,
        creating: Boolean,
        expectedLoan: LoanEntity,
        writeAssociatedData: suspend () -> Unit,
    ): LoanSaveResult = write {
        require(record.amount.isFinite() && record.amount > 0 && !record.isDeleted)
        require(record.convertedAmount == null || record.convertedAmount.isFinite())
        require(record.loanId == expectedLoan.id && !expectedLoan.isDeleted)
        // Conversion and transaction direction were calculated for this parent.
        // Reject a concurrent edit/deletion instead of saving against stale data.
        require(database.loanDao.findById(record.loanId) == expectedLoan)
        val previous = database.loanRecordDao.findById(record.id)
        require(if (creating) previous == null else {
            previous != null && !previous.isDeleted && previous.loanId == record.loanId
        })
        validateAccount(record.accountId)
        database.writeLoanRecordDao.save(record.copy(isSynced = false))
        writeAssociatedData()
    }

    private suspend fun validateAccount(id: UUID?) {
        if (id == null) return
        val account = database.accountDao.findById(id)
        require(account != null && !account.isDeleted) { "Account no longer exists" }
    }

    private suspend fun write(rows: suspend () -> Unit): LoanSaveResult {
        var committed = false
        var published = true
        try {
            withContext(dispatchers.io) {
                database.withTransaction { rows() }
                committed = true
            }
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                memoFactory.invalidateAll()
                if (committed) {
                    try {
                        observer.post(DataWriteEvent.AllDataChange)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        published = false
                    }
                }
            }
        }
        return if (published) LoanSaveResult.Saved else LoanSaveResult.RefreshFailed
    }
}
