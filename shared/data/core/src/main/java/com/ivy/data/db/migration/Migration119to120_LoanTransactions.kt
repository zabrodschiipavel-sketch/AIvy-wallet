package com.ivy.domain.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration119to120_LoanTransactions : Migration(119, 120) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Exported 119 already has loanId; older migration paths can lack it.
        addColumnIfMissing(database, "transactions", "loanId", "TEXT")
        addColumnIfMissing(database, "transactions", "loanRecordId", "TEXT")
        addColumnIfMissing(database, "loan_records", "interest", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(database, "loan_records", "accountId", "TEXT")
        addColumnIfMissing(database, "loan_records", "convertedAmount", "REAL")
        addColumnIfMissing(database, "loans", "accountId", "TEXT")
    }

    private fun addColumnIfMissing(
        database: SupportSQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        val exists = database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val columnName = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(columnName) == column) found = true
            }
            found
        }
        if (!exists) database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}
