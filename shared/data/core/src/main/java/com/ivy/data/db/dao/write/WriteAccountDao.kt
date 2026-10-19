package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.model.AccountHistoryException
import java.util.UUID

@Dao
interface WriteAccountDao {
    @Transaction
    suspend fun save(value: AccountEntity) {
        val previous = accountForWrite(value.id)
        val previousCurrency = previous?.currency ?: baseCurrencyForWrite()
        if (previous != null && previousCurrency != value.currency && hasFinancialReferences(value.id)) {
            throw AccountHistoryException("An account with financial history cannot change currency. Create a new account and transfer the balance.")
        }
        if (value.archived && hasActiveRules(value.id)) {
            throw AccountHistoryException("Move or remove this account's active planned payment rules before archiving it.")
        }
        upsert(value)
    }

    @Transaction
    suspend fun saveMany(values: List<AccountEntity>) {
        values.forEach { save(it) }
    }

    @Upsert
    suspend fun upsert(value: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun accountForWrite(id: UUID): AccountEntity?

    @Query("SELECT currency FROM settings LIMIT 1")
    suspend fun baseCurrencyForWrite(): String?

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE accountId = :id OR toAccountId = :id) OR EXISTS(SELECT 1 FROM planned_payment_rules WHERE accountId = :id) OR EXISTS(SELECT 1 FROM loans WHERE accountId = :id) OR EXISTS(SELECT 1 FROM loan_records WHERE accountId = :id) OR EXISTS(SELECT 1 FROM budgets WHERE accountIdsSerialized LIKE '%' || :id || '%')")
    suspend fun hasFinancialReferences(id: UUID): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM planned_payment_rules WHERE accountId = :id AND isDeleted = 0)")
    suspend fun hasActiveRules(id: UUID): Boolean

    @Transaction
    suspend fun deleteById(id: UUID) {
        if (hasFinancialReferences(id)) {
            throw AccountHistoryException("This account has financial history. Archive it to preserve its transactions.")
        }
        deleteUnreferenced(id)
    }

    @Transaction
    suspend fun removeOrArchive(id: UUID) {
        val account = accountForWrite(id) ?: return
        if (hasFinancialReferences(id)) save(account.copy(archived = true)) else deleteUnreferenced(id)
    }

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteUnreferenced(id: UUID)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}
