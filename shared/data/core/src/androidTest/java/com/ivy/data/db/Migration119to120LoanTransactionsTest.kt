package com.ivy.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.ivy.domain.db.migration.Migration119to120_LoanTransactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Migration119to120LoanTransactionsTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IvyRoomDatabase::class.java,
        listOf(IvyRoomDatabase.DeleteSEMigration()),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun exported119PreservesExistingLoanId() = verifyMigration(columnsAlreadyPresent = false)

    @Test
    fun existingColumnsAndLoanLinksArePreserved() = verifyMigration(columnsAlreadyPresent = true)

    private fun verifyMigration(columnsAlreadyPresent: Boolean) {
        val databaseName = "loan-transactions-119-$columnsAlreadyPresent"
        helper.createDatabase(databaseName, 119).use { db ->
            if (columnsAlreadyPresent) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN loanRecordId TEXT")
                db.execSQL("ALTER TABLE loan_records ADD COLUMN interest INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loan_records ADD COLUMN accountId TEXT")
                db.execSQL("ALTER TABLE loan_records ADD COLUMN convertedAmount REAL")
                db.execSQL("ALTER TABLE loans ADD COLUMN accountId TEXT")
            }
            db.execSQL(
                """
                INSERT INTO transactions (id, accountId, type, amount, loanId, isSynced, isDeleted)
                VALUES ('transaction', 'account', 'EXPENSE', 12.5, 'loan', 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO loans (id, name, amount, type, color, orderNum, isSynced, isDeleted)
                VALUES ('loan', 'Original loan', 50.0, 'BORROW', 0, 1.0, 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO loan_records (id, loanId, amount, dateTime, isSynced, isDeleted)
                VALUES ('record', 'loan', 12.5, 1700000000000, 0, 0)
                """.trimIndent()
            )
            if (columnsAlreadyPresent) {
                db.execSQL("UPDATE transactions SET loanRecordId = 'record'")
                db.execSQL("UPDATE loan_records SET interest = 1, accountId = 'account', convertedAmount = 25.0")
                db.execSQL("UPDATE loans SET accountId = 'account'")
            }
        }
        helper.runMigrationsAndValidate(
            databaseName,
            120,
            true,
            Migration119to120_LoanTransactions()
        ).use { db ->
            db.query("SELECT loanId, loanRecordId, amount FROM transactions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("loan", cursor.getString(0))
                assertEquals(if (columnsAlreadyPresent) "record" else null, cursor.getString(1))
                assertEquals(12.5, cursor.getDouble(2), 0.0)
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT interest, accountId, convertedAmount FROM loan_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(if (columnsAlreadyPresent) 1 else 0, cursor.getInt(0))
                assertEquals(if (columnsAlreadyPresent) "account" else null, cursor.getString(1))
                if (columnsAlreadyPresent) {
                    assertEquals(25.0, cursor.getDouble(2), 0.0)
                } else {
                    assertTrue(cursor.isNull(2))
                }
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT accountId FROM loans").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(if (columnsAlreadyPresent) "account" else null, cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        }
    }
}
