package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ivy.data.db.entity.SettingsEntity
import java.util.UUID

@Dao
interface WriteSettingsDao {
    @Transaction
    suspend fun save(value: SettingsEntity) {
        val old = baseCurrencyForWrite() ?: value.currency
        freezeInheritedAccountCurrencies(old)
        upsert(value)
    }

    @Query("SELECT currency FROM settings LIMIT 1")
    suspend fun baseCurrencyForWrite(): String?

    @Query("UPDATE accounts SET currency = :currency WHERE currency IS NULL")
    suspend fun freezeInheritedAccountCurrencies(currency: String)

    @Upsert
    suspend fun upsert(value: SettingsEntity)

    @Transaction
    suspend fun saveMany(value: List<SettingsEntity>) {
        value.forEach { save(it) }
    }

    @Query("DELETE FROM settings WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}
