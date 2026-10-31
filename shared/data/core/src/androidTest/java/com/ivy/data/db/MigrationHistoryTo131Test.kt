package com.ivy.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Minimal history fixture, not a financial matrix. Other entity data has separate tests. */
@RunWith(Parameterized::class)
class MigrationHistoryTo131Test(private val sourceVersion: Int) {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IvyRoomDatabase::class.java,
        listOf(IvyRoomDatabase.DeleteSEMigration()),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun initialHistorySurvivesCompleteMigrationPath() {
        val databaseName = "history-$sourceVersion-to-$TargetVersion"
        helper.createDatabase(databaseName, sourceVersion).use { oldDb ->
            seedSettings(oldDb)
            seedAccount(oldDb, FirstAccount, "Cash", 1.0)
            seedAccount(oldDb, SecondAccount, "Savings", 2.0)
            seedOperation(oldDb, ExpenseId, "EXPENSE", ExpenseAmount, null)
            seedOperation(oldDb, TransferId, "TRANSFER", TransferAmount, SecondAccount)
        }
        // One direct helper invocation: Room chooses the complete production migration path.
        helper.runMigrationsAndValidate(
            databaseName,
            TargetVersion,
            true,
            *IvyRoomDatabase.migrations()
        ).use { migrated ->
            verifyAccounts(migrated)
            verifyHistory(migrated)
            assertCount(migrated, "restore_preferences_journal", 0)
        }
    }

    private fun seedSettings(db: SupportSQLiteDatabase) {
        insertKnownColumns(
            db,
            "settings",
            mapOf(
                "id" to SettingsId,
                "theme" to "LIGHT",
                "currency" to "USD",
                "bufferAmount" to 0.0,
                "name" to "Migration history",
                "isSynced" to 0L,
                "isDeleted" to 0L
            )
        )
    }

    private fun seedAccount(db: SupportSQLiteDatabase, id: String, name: String, order: Double) {
        insertKnownColumns(
            db,
            "accounts",
            mapOf(
                "id" to id, "name" to name, "currency" to "USD",
                "color" to 0L, "icon" to null, "orderNum" to order,
                "includeInBalance" to 1L, "isSynced" to 0L, "isDeleted" to 0L,
                "seAccountId" to null
            )
        )
    }

    private fun seedOperation(
        db: SupportSQLiteDatabase,
        id: String,
        type: String,
        amount: Double,
        destination: String?
    ) {
        insertKnownColumns(
            db,
            "transactions",
            mapOf(
                "id" to id, "accountId" to FirstAccount, "type" to type,
                "amount" to amount, "toAccountId" to destination,
                "toAmount" to if (destination != null) amount else null,
                "title" to type, "description" to null, "dateTime" to HappenedAt,
                "categoryId" to null, "dueDate" to null, "recurringRuleId" to null,
                "paidForDateTime" to null, "attachmentUrl" to null,
                "loanId" to null, "loanRecordId" to null,
                "seTransactionId" to null, "seAutoCategoryId" to null,
                "isSynced" to 0L, "isDeleted" to 0L
            )
        )
    }

    /** Only actual old columns are inserted; unknown columns/affinities are never guessed. */
    private fun insertKnownColumns(
        db: SupportSQLiteDatabase,
        table: String,
        fixture: Map<String, Any?>
    ) {
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val affinity = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                check(fixture.containsKey(column)) {
                    "Unsupported fixture shape at schema $sourceVersion: $table.$column ($affinity)"
                }
                val value = fixture[column]
                val expectedAffinity = when (value) {
                    is String -> "TEXT"
                    is Long -> "INTEGER"
                    is Double -> "REAL"
                    else -> affinity
                }
                check(affinity == expectedAffinity) {
                    "Unsupported affinity at schema $sourceVersion: $table.$column=$affinity"
                }
                check(value != null || cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 0) {
                    "Unsupported required column at schema $sourceVersion: $table.$column"
                }
                columns.add(column)
            }
        }
        check(columns.isNotEmpty()) { "Missing fixture table $table at schema $sourceVersion" }
        if (table == "transactions") {
            check(columns.containsAll(listOf("dateTime", "toAccountId"))) {
                "Unsupported history fixture at schema $sourceVersion: $columns"
            }
        }
        val names = columns.joinToString { "`$it`" }
        val placeholders = columns.joinToString { "?" }
        db.execSQL(
            "INSERT INTO `$table` ($names) VALUES ($placeholders)",
            columns.map { fixture[it] }.toTypedArray()
        )
    }

    private fun verifyAccounts(db: SupportSQLiteDatabase) {
        assertCount(db, "accounts", 2)
        db.query("SELECT id, name, currency, archived FROM accounts ORDER BY id").use { cursor ->
            for ((id, name) in listOf(FirstAccount to "Cash", SecondAccount to "Savings")) {
                assertTrue(cursor.moveToNext())
                assertEquals(id, cursor.getString(0))
                assertEquals(name, cursor.getString(1))
                // Migration 130 to 131 freezes formerly inherited currencies from settings.
                assertEquals("USD", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
            assertFalse(cursor.moveToNext())
        }
    }

    /** Named-column raw reads prove that the initial happened history remains accessible. */
    private fun verifyHistory(db: SupportSQLiteDatabase) {
        assertCount(db, "transactions", 2)
        val sql = "SELECT id, accountId, type, amount, toAccountId, toAmount, dateTime, dueDate " +
            "FROM transactions ORDER BY id"
        db.query(sql).use { cursor ->
            for ((id, type, amount) in listOf(
                Triple(ExpenseId, "EXPENSE", ExpenseAmount),
                Triple(TransferId, "TRANSFER", TransferAmount)
            )) {
                assertTrue(cursor.moveToNext())
                assertEquals(id, cursor.getString(0))
                assertEquals(FirstAccount, cursor.getString(1))
                assertEquals(type, cursor.getString(2))
                assertEquals(amount, cursor.getDouble(3), 0.0)
                assertEquals(if (id == TransferId) SecondAccount else null, cursor.getString(4))
                if (id == TransferId && sourceVersion >= TransferAmountVersion) {
                    assertEquals(TransferAmount, cursor.getDouble(5), 0.0)
                } else {
                    assertTrue(cursor.isNull(5))
                }
                assertEquals(HappenedAt, cursor.getLong(6))
                assertTrue(cursor.isNull(7))
            }
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertCount(db: SupportSQLiteDatabase, table: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Row count for $table from $sourceVersion", expected, cursor.getInt(0))
        }
    }

    companion object {
        private const val FirstVersion = 106
        private const val LastSourceVersion = 130
        private const val TargetVersion = 131
        private const val TransferAmountVersion = 114
        private const val HappenedAt = 1700000000000L
        private const val ExpenseAmount = 12.5
        private const val TransferAmount = 25.0
        private const val FirstAccount = "00000000-0000-0000-0000-000000000001"
        private const val SecondAccount = "00000000-0000-0000-0000-000000000002"
        private const val ExpenseId = "00000000-0000-0000-0000-000000000003"
        private const val TransferId = "00000000-0000-0000-0000-000000000004"
        private const val SettingsId = "00000000-0000-0000-0000-000000000005"

        @JvmStatic
        @Parameterized.Parameters(name = "schema {0} to 131")
        fun sourceVersions(): List<Array<Int>> =
            (FirstVersion..LastSourceVersion).map { arrayOf(it) }
    }
}
