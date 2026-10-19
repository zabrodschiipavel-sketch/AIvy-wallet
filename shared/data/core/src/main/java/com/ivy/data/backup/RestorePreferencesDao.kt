package com.ivy.data.backup

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RestorePreferencesDao {
    @Query("SELECT * FROM restore_preferences_journal WHERE id = 1")
    suspend fun getPending(): RestorePreferencesEntity?

    @Upsert
    suspend fun save(value: RestorePreferencesEntity)

    @Query("DELETE FROM restore_preferences_journal WHERE id = 1")
    suspend fun delete()
}
