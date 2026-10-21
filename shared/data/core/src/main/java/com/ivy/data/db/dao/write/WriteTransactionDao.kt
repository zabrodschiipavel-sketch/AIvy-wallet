package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ivy.data.db.entity.TransactionEntity
import java.util.UUID

@Dao
interface WriteTransactionDao {
    @Upsert
    suspend fun save(value: TransactionEntity)

    @Upsert
    suspend fun saveMany(value: List<TransactionEntity>)

    @Transaction
    suspend fun deletedByRecurringRuleIdAndNoDateTime(recurringRuleId: UUID) {
        deleteTagsByRecurringRuleIdAndNoDateTime(recurringRuleId)
        deletedByRecurringRuleIdAndNoDateTimeRaw(recurringRuleId)
    }

    @Query(
        "DELETE FROM tags_association WHERE associatedId IN " +
            "(SELECT id FROM transactions WHERE recurringRuleId = :recurringRuleId AND dateTime IS NULL)"
    )
    suspend fun deleteTagsByRecurringRuleIdAndNoDateTime(recurringRuleId: UUID)

    @Query("DELETE FROM transactions WHERE recurringRuleId = :recurringRuleId AND dateTime IS NULL")
    suspend fun deletedByRecurringRuleIdAndNoDateTimeRaw(recurringRuleId: UUID)

    @Transaction
    suspend fun deleteById(id: UUID) {
        deleteTagsById(id)
        deleteByIdRaw(id)
    }

    @Query("DELETE FROM tags_association WHERE associatedId IN (SELECT id FROM transactions WHERE id = :id)")
    suspend fun deleteTagsById(id: UUID)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteByIdRaw(id: UUID)

    @Transaction
    suspend fun deleteAllByAccountId(accountId: UUID) {
        deleteTagsAllByAccountId(accountId)
        deleteAllByAccountIdRaw(accountId)
    }

    @Query(
        "DELETE FROM tags_association WHERE associatedId IN " +
            "(SELECT id FROM transactions WHERE accountId = :accountId)"
    )
    suspend fun deleteTagsAllByAccountId(accountId: UUID)

    @Query("DELETE FROM transactions WHERE accountId = :accountId")
    suspend fun deleteAllByAccountIdRaw(accountId: UUID)

    @Query(
        "DELETE FROM transactions WHERE loanId = :loanId OR loanRecordId IN " +
            "(SELECT id FROM loan_records WHERE loanId = :loanId)"
    )
    suspend fun deleteAllForLoan(loanId: UUID)

    @Query("DELETE FROM transactions WHERE loanRecordId = :loanRecordId")
    suspend fun deleteAllForLoanRecord(loanRecordId: UUID)

    @Transaction
    suspend fun deleteAll() {
        deleteTagsForAll()
        deleteAllRaw()
    }

    @Query("DELETE FROM tags_association WHERE associatedId IN (SELECT id FROM transactions)")
    suspend fun deleteTagsForAll()

    @Query("DELETE FROM transactions")
    suspend fun deleteAllRaw()
}
