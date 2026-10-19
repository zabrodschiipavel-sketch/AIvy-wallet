package com.ivy.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ivy.data.db.migration.Migration130to131ForkSafety
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration130to131CurrencyTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IvyRoomDatabase::class.java,
        listOf(IvyRoomDatabase.DeleteSEMigration()),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun inheritedCurrencyIsFrozenFromSettings() = verifyMigration(withSettings = true)

    @Test
    fun missingSettingsDoesNotInventCurrency() = verifyMigration(withSettings = false)

    private fun verifyMigration(withSettings: Boolean) {
        val name = "currency-130-to-131-$withSettings"
        helper.createDatabase(name, 130).use { db ->
            if (withSettings) {
                db.execSQL(
                    "INSERT INTO settings (id, theme, currency, bufferAmount, name, isSynced, isDeleted) " +
                        "VALUES ('settings', 'AUTO', 'USD', 0, '', 0, 0)"
                )
            }
            db.execSQL(
                "INSERT INTO accounts (id, name, currency, color, orderNum, includeInBalance, isSynced, isDeleted) " +
                    "VALUES ('inherited', 'Inherited', NULL, 1, 0, 1, 0, 0), " +
                    "('explicit', 'Explicit', 'GBP', 2, 1, 1, 0, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 131, true, Migration130to131ForkSafety()).use { db ->
            db.query("SELECT currency FROM accounts WHERE id = 'inherited'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(if (withSettings) "USD" else null, cursor.getString(0))
            }
            db.query("SELECT currency FROM accounts WHERE id = 'explicit'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("GBP", cursor.getString(0))
            }
        }
    }
}
