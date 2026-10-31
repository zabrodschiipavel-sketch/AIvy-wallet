package com.ivy.data.backup

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Written in the same Room transaction as restored data; removed after durable prefs commit. */
@Entity(tableName = "restore_preferences_journal")
data class RestorePreferencesEntity(
    @PrimaryKey val id: Int,
    val json: String,
)
