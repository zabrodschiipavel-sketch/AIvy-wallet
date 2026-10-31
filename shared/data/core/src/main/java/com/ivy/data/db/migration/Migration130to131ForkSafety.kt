package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val FromVersion = 130
private const val ToVersion = 131

class Migration130to131ForkSafety : Migration(FromVersion, ToVersion) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE accounts SET currency = (SELECT currency FROM settings LIMIT 1) " +
                "WHERE currency IS NULL AND EXISTS (SELECT 1 FROM settings)"
        )
        db.execSQL("ALTER TABLE accounts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS restore_preferences_journal " +
                "(id INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(id))"
        )
    }
}
