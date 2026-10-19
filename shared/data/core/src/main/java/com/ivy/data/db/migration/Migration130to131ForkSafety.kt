package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val FROM_VERSION = 130
private const val TO_VERSION = 131

class Migration130to131ForkSafety : Migration(FROM_VERSION, TO_VERSION) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS restore_preferences_journal " +
                "(id INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(id))"
        )
    }
}
