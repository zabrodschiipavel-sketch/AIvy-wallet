package com.ivy.data.loan

import androidx.room.withTransaction
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.repository.RepositoryMemoFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class LoanDeletionCommand @Inject constructor(
    private val database: IvyRoomDatabase,
    private val memoFactory: RepositoryMemoFactory,
    private val observer: DataObserver,
    private val dispatchers: DispatchersProvider,
) {
    suspend fun deleteLoan(id: UUID) = delete {
        database.writeTransactionDao.deleteAllForLoan(id)
        database.writeLoanRecordDao.deleteAllForLoan(id)
        database.writeLoanDao.deleteById(id)
    }

    suspend fun deleteLoanRecord(id: UUID) = delete {
        database.writeTransactionDao.deleteAllForLoanRecord(id)
        database.writeLoanRecordDao.deleteById(id)
    }

    private suspend fun delete(rows: suspend () -> Unit) {
        var committed = false
        try {
            withContext(dispatchers.io) {
                database.withTransaction { rows() }
                committed = true
            }
        } finally {
            withContext(NonCancellable + dispatchers.io) {
                // Deliberately refresh caches even on rollback or cancellation at Room's
                // return boundary: commit can be ambiguous there. Never suppress cancellation
                // or publish a success event unless Room has confirmed the commit.
                memoFactory.invalidateAll()
                // Both invalidation and reentrant observers run outside Room's lock.
                if (committed) observer.post(DataWriteEvent.AllDataChange)
            }
        }
    }
}
