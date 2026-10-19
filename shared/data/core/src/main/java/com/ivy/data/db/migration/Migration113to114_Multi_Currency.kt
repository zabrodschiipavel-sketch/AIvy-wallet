package com.ivy.domain.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration113to114_Multi_Currency : Migration(113, 114) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Both schema-113 variants exist: the exported schema already has currency,
        // while databases arriving through older migrations do not.
        val hasCurrency = database.query("PRAGMA table_info(accounts)").use { cursor ->
            val columnName = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(columnName) == "currency") found = true
            }
            found
        }
        if (!hasCurrency) database.execSQL("ALTER TABLE accounts ADD COLUMN currency TEXT")
        database.execSQL("ALTER TABLE transactions ADD COLUMN toAmount REAL")
    }
}
