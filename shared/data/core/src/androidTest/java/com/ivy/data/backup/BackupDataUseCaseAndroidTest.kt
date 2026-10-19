package com.ivy.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ivy.base.TestDispatchersProvider
import com.ivy.base.di.KotlinxSerializationModule
import com.ivy.base.legacy.SharedPrefs
import com.ivy.data.DataObserver
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.repository.fake.fakeRepositoryMemoFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupDataUseCaseAndroidTest {

    private lateinit var db: IvyRoomDatabase
    private lateinit var useCase: BackupDataUseCase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IvyRoomDatabase::class.java).build()
        val appContext = InstrumentationRegistry.getInstrumentation().context
        useCase = BackupDataUseCase(
            accountDao = db.accountDao,
            budgetDao = db.budgetDao,
            categoryDao = db.categoryDao,
            loanRecordDao = db.loanRecordDao,
            loanDao = db.loanDao,
            plannedPaymentRuleDao = db.plannedPaymentRuleDao,
            settingsDao = db.settingsDao,
            transactionDao = db.transactionDao,
            transactionWriter = db.writeTransactionDao,
            sharedPrefs = SharedPrefs(appContext),
            categoryWriter = db.writeCategoryDao,
            settingsWriter = db.writeSettingsDao,
            budgetWriter = db.writeBudgetDao,
            loanWriter = db.writeLoanDao,
            loanRecordWriter = db.writeLoanRecordDao,
            plannedPaymentRuleWriter = db.writePlannedPaymentRuleDao,
            context = appContext,
            json = KotlinxSerializationModule.provideJson(),
            dispatchersProvider = TestDispatchersProvider,
            dataObserver = DataObserver(),
            tagsReader = db.tagDao,
            tagAssociationReader = db.tagAssociationDao,
            tagsWriter = db.writeTagDao,
            tagAssociationWriter = db.writeTagAssociationDao,
            database = db,
            transactionRunner = BackupTransactionRunner(db),
            recovery = RestorePreferencesRecovery(
                db, SharedPrefs(appContext), KotlinxSerializationModule.provideJson(),
                TestDispatchersProvider
            ),
            memoFactory = fakeRepositoryMemoFactory(),
        )
    }

    @Test
    fun restoreTransactionRollsBackOnFailure() = runBlocking {
        val account = com.ivy.data.db.entity.AccountEntity(
            name = "Rollback probe",
            currency = "USD",
            color = 0
        )
        val previousJournal = db.restorePreferencesDao.getPending()
        try {
            BackupTransactionRunner(db).inTransaction {
                db.writeAccountDao.save(account)
                db.restorePreferencesDao.save(RestorePreferencesEntity(id = 1, json = "{}"))
                error("Injected failure after restore writes")
            }
        } catch (e: IllegalStateException) {
            org.junit.Assert.assertEquals("Injected failure after restore writes", e.message)
        }
        org.junit.Assert.assertNull(db.accountDao.findById(account.id))
        org.junit.Assert.assertEquals(previousJournal, db.restorePreferencesDao.getPending())
    }

    @Test
    fun productionImportRollsBackAtEveryGroupedWriter() = runBlocking {
        val json = KotlinxSerializationModule.provideJson()
        val prefs = SharedPrefs(InstrumentationRegistry.getInstrumentation().context)
        val originalPrefs = RestorePreferenceKeys.associateWith { prefs.getBoolean(it, false) }
        val sentinel = com.ivy.data.db.entity.AccountEntity(
            name = "Sentinel account",
            currency = "USD",
            color = 42
        )
        val history = com.ivy.data.db.entity.TransactionEntity(
            accountId = sentinel.id,
            type = com.ivy.base.model.TransactionType.EXPENSE,
            amount = 7.0,
            dateTime = java.time.Instant.parse("2020-01-01T00:00:00Z"),
            title = "Sentinel history"
        )
        db.writeAccountDao.save(sentinel)
        db.writeTransactionDao.saveMany(listOf(history))
        try {
            org.junit.Assert.assertTrue(prefs.commitBooleans(RestorePreferenceKeys.associateWith { false }))
            val before = useCase.generateJsonBackup()
            val payload = json.encodeToString(completeRestorePayload())
            val tables = listOf(
                "accounts", "categories", "planned_payment_rules", "loans", "loan_records",
                "budgets", "transactions", "tags", "tags_association", "settings",
                "exchange_rates", "restore_preferences_journal"
            )
            for (table in tables) {
                val sql = db.openHelper.writableDatabase
                sql.execSQL(
                    "CREATE TRIGGER fail_restore BEFORE INSERT ON $table " +
                        "BEGIN SELECT RAISE(ABORT,'injected'); END;"
                )
                val progress = mutableListOf<Double>()
                try {
                    var failure: Exception? = null
                    try {
                        useCase.importJson(payload, onProgress = { progress += it })
                    } catch (e: Exception) {
                        failure = e
                    }
                    org.junit.Assert.assertNotNull("Trigger was not reached: $table", failure)
                    org.junit.Assert.assertTrue(
                        "Unexpected failure at $table: $failure",
                        generateSequence<Throwable>(failure) { it.cause }
                            .any { it.message.orEmpty().contains("injected") }
                    )
                } finally {
                    sql.execSQL("DROP TRIGGER IF EXISTS fail_restore")
                }
                org.junit.Assert.assertFalse("Success progress on rollback: $table", 1.0 in progress)
                // Check before export: exporting would otherwise replay a leaked journal.
                org.junit.Assert.assertNull(db.restorePreferencesDao.getPending())
                RestorePreferenceKeys.forEach {
                    org.junit.Assert.assertFalse("Preference changed at $table: $it", prefs.getBoolean(it, true))
                }
                org.junit.Assert.assertEquals("Snapshot changed at $table", before, useCase.generateJsonBackup())
                org.junit.Assert.assertEquals(listOf(sentinel), db.accountDao.findAll())
                org.junit.Assert.assertEquals(listOf(history), db.transactionDao.findAll())
            }
        } finally {
            org.junit.Assert.assertTrue(prefs.commitBooleans(originalPrefs))
        }
    }

    @Test
    fun repeatedProductionImportPreservesExactRowsAndIdentities() = runBlocking {
        val json = KotlinxSerializationModule.provideJson()
        val prefs = SharedPrefs(InstrumentationRegistry.getInstrumentation().context)
        val originalPrefs = RestorePreferenceKeys.associateWith { prefs.getBoolean(it, false) }
        try {
            val incoming = completeRestorePayload()
            val progress = mutableListOf<Double>()
            val first = useCase.importJson(json.encodeToString(incoming), onProgress = { progress += it })
            first.shouldBeSuccessful()
            first.transactionsImported shouldBeGreaterThan 0
            first.accountsImported shouldBeGreaterThan 0
            first.categoriesImported shouldBeGreaterThan 0
            org.junit.Assert.assertEquals(1.0, progress.last(), 0.0)
            val snapshot = useCase.generateJsonBackup()
            val repeat = useCase.importJson(json.encodeToString(incoming))
            repeat.shouldBeSuccessful()
            org.junit.Assert.assertEquals(0, repeat.transactionsImported)
            org.junit.Assert.assertEquals(0, repeat.accountsImported)
            org.junit.Assert.assertEquals(0, repeat.categoriesImported)
            org.junit.Assert.assertEquals(incoming.transactions.size, repeat.transactionsSkipped)
            org.junit.Assert.assertEquals(snapshot, useCase.generateJsonBackup())
            org.junit.Assert.assertEquals(incoming.accounts, db.accountDao.findAll())
            org.junit.Assert.assertEquals(incoming.transactions, db.transactionDao.findAll())
        } finally {
            org.junit.Assert.assertTrue(prefs.commitBooleans(originalPrefs))
        }
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun backup450_150() = runBlocking {
        backupTestCase("450-150")
    }

    private suspend fun backupTestCase(version: String) {
        importBackupZipTestCase(version)
        importBackupJsonTestCase(version)

        // close and re-open the db to ensure fresh data
        closeDb()
        createDb()
        exportsAndImportsTestCase(version)
    }

    private suspend fun importBackupZipTestCase(version: String) {
        // given
        val backupUri = copyTestResourceToInternalStorage("backups/$version.zip")

        // when
        val res = useCase.importBackupFile(backupUri, onProgress = {})

        // then
        res.shouldBeSuccessful()
    }

    private suspend fun importBackupJsonTestCase(version: String) {
        // given
        val backupUri = copyTestResourceToInternalStorage("backups/$version.json")

        // when
        val res = useCase.importBackupFile(backupUri, onProgress = {})

        // then
        res.shouldBeSuccessful()
    }

    private suspend fun exportsAndImportsTestCase(version: String) {
        // given
        val backupUri = copyTestResourceToInternalStorage("backups/$version.zip")
        // preload data
        useCase.importBackupFile(backupUri, onProgress = {}).shouldBeSuccessful()
        val exportedFileUri = tempAndroidFile("exported", ".zip").toUri()

        // then
        useCase.exportToFile(exportedFileUri)
        val reImportRes = useCase.importBackupFile(backupUri, onProgress = {})

        // then
        reImportRes.shouldBeSuccessful()
    }

    private fun copyTestResourceToInternalStorage(resPath: String): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager = context.assets
        val inputStream = assetManager.open(resPath)
        val outputFile = tempAndroidFile("temp-backup", resPath.split(".").last())
        outputFile.outputStream().use { fileOut ->
            fileOut.write(inputStream.readBytes())
        }
        return Uri.fromFile(outputFile)
    }

    private fun tempAndroidFile(prefix: String, suffix: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File.createTempFile(prefix, suffix, context.filesDir)
    }

    private fun ImportResult.shouldBeSuccessful() {
        failedRows.shouldBeEmpty()
        org.junit.Assert.assertTrue(categoriesImported >= 0)
        org.junit.Assert.assertTrue(accountsImported >= 0)
        (transactionsImported + transactionsSkipped) shouldBeGreaterThan 0
        org.junit.Assert.assertNull(error)
        org.junit.Assert.assertFalse(preferencesPending)
    }
}
