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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupDataUseCaseAndroidTest {

    private lateinit var db: IvyRoomDatabase
    private lateinit var useCase: BackupDataUseCase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IvyRoomDatabase::class.java).build()
        useCase = newUseCase()
    }

    private fun newUseCase(
        runner: BackupTransactionRunner = BackupTransactionRunner(db),
        observer: DataObserver = DataObserver(),
        memoFactory: com.ivy.data.repository.RepositoryMemoFactory = fakeRepositoryMemoFactory(),
        recovery: RestorePreferencesRecovery = newRecovery(),
    ): BackupDataUseCase {
        val appContext = InstrumentationRegistry.getInstrumentation().context
        return BackupDataUseCase(
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
            dataObserver = observer,
            tagsReader = db.tagDao,
            tagAssociationReader = db.tagAssociationDao,
            tagsWriter = db.writeTagDao,
            tagAssociationWriter = db.writeTagAssociationDao,
            database = db,
            transactionRunner = runner,
            recovery = recovery,
            memoFactory = memoFactory,
        )
    }

    private fun newRecovery() = RestorePreferencesRecovery(
        db,
        SharedPrefs(InstrumentationRegistry.getInstrumentation().context),
        KotlinxSerializationModule.provideJson(),
        TestDispatchersProvider
    )

    @Test
    fun cancellationAfterRoomCommitReplaysJournal() = runBlocking {
        cancellationAfterRoomCommit(retainJournal = false)
    }

    @Test
    fun cancellationAfterRoomCommitRetainsFailedRecovery() = runBlocking {
        cancellationAfterRoomCommit(retainJournal = true)
    }

    private suspend fun cancellationAfterRoomCommit(retainJournal: Boolean) = withTimeout(10_000) {
        val prefs = SharedPrefs(InstrumentationRegistry.getInstrumentation().context)
        val beforePrefs = RestorePreferenceKeys.associateWith { prefs.getBoolean(it, false) }
        val restoredPrefs = beforePrefs.mapValues { !it.value }
        val observer = DataObserver()
        val recovery = newRecovery()
        val factory = com.ivy.data.repository.RepositoryMemoFactory(observer, TestDispatchersProvider)
        val memo = factory.createMemo<com.ivy.data.model.Account, com.ivy.data.model.AccountId>(
            { com.ivy.data.DataWriteEvent.SaveAccounts(it) },
            { com.ivy.data.DataWriteEvent.DeleteAccounts(it) },
        )
        memo.findAll({ emptyList() }, { toList() })
        org.junit.Assert.assertTrue(memo.findAllMemoized)
        lateinit var importJob: Job
        val runner = object : BackupTransactionRunner(db) {
            private var cancelOnce = true
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                val result = super.inTransaction(block)
                if (cancelOnce) {
                    cancelOnce = false
                    val cancellation = CancellationException("Injected after Room commit")
                    importJob.cancel(cancellation)
                    throw cancellation
                }
                return result
            }
        }
        val importer = newUseCase(runner, observer, factory, recovery)
        val account = com.ivy.data.db.entity.AccountEntity(
            name = "Committed despite cancellation",
            currency = "USD",
            color = 0
        )
        val payload = IvyWalletCompleteData(
            formatVersion = 2,
            accounts = listOf(account),
            sharedPrefs = HashMap(restoredPrefs.mapValues { it.value.toString() }),
        )
        var events = 0
        val observed = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            observer.writeEvents.collect { event ->
                org.junit.Assert.assertEquals(com.ivy.data.DataWriteEvent.AllDataChange, event)
                events++
                // Re-enter the same mutex from the observer. Notification must be outside it.
                recovery.withRestoreLock { }
                observed.complete(Unit)
            }
        }
        if (retainJournal) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_replay BEFORE DELETE ON restore_preferences_journal " +
                    "BEGIN SELECT RAISE(ABORT,'injected replay failure'); END;"
            )
        }
        try {
            var cancelled = false
            val child = launch(start = CoroutineStart.LAZY) {
                try {
                    importer.importJson(KotlinxSerializationModule.provideJson().encodeToString(payload))
                    error("Expected cancellation")
                } catch (expected: CancellationException) {
                    cancelled = true
                }
            }
            importJob = child
            child.start()
            child.join()
            observed.await()
            org.junit.Assert.assertTrue(cancelled)
            org.junit.Assert.assertTrue(child.isCancelled)
            org.junit.Assert.assertEquals(account, db.accountDao.findById(account.id))
            org.junit.Assert.assertEquals(retainJournal, db.restorePreferencesDao.getPending() != null)
            restoredPrefs.forEach { (key, value) ->
                org.junit.Assert.assertEquals(value, prefs.getBoolean(key, !value))
            }
            org.junit.Assert.assertFalse(memo.findAllMemoized)
            org.junit.Assert.assertEquals(1, events)
            // This parent coroutine remains usable after the child cancels itself.
            recovery.withRestoreLock { }
        } finally {
            collector.cancel()
            if (retainJournal) {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_replay")
                recovery.recoverPending()
            }
            org.junit.Assert.assertTrue(prefs.commitBooleans(beforePrefs))
        }
    }

    @Test
    fun restoreSnapshotPreservesDeletedIdentitiesButExportExcludesThem() = runBlocking {
        val payload = completeRestorePayload()
        val json = KotlinxSerializationModule.provideJson()
        db.writeAccountDao.saveMany(payload.accounts)
        db.writeCategoryDao.saveMany(payload.categories)
        db.writeTransactionDao.saveMany(payload.transactions)
        val sql = db.openHelper.writableDatabase
        listOf("accounts", "categories", "transactions").forEach {
            sql.execSQL("UPDATE $it SET isDeleted = 1")
        }
        val exported = json.decodeFromString<IvyWalletCompleteData>(useCase.generateJsonBackup())
        org.junit.Assert.assertTrue(exported.accounts.isEmpty())
        org.junit.Assert.assertTrue(exported.categories.isEmpty())
        org.junit.Assert.assertTrue(exported.transactions.isEmpty())
        // Isolate each collision so an earlier entity cannot hide a filtered reader.
        val probes = listOf(
            IvyWalletCompleteData(formatVersion = 2, accounts = payload.accounts),
            IvyWalletCompleteData(formatVersion = 2, categories = payload.categories),
            IvyWalletCompleteData(formatVersion = 2, transactions = payload.transactions),
        )
        for ((index, probe) in probes.withIndex()) {
            var failure: Exception? = null
            try {
                useCase.importJson(json.encodeToString(probe), onProgress = {})
            } catch (e: Exception) {
                failure = e
            }
            org.junit.Assert.assertNotNull("Restore missed tombstone probe $index", failure)
            org.junit.Assert.assertTrue(
                "Expected identity conflict: $failure",
                generateSequence<Throwable>(failure) { it.cause }
                    .any { it.message.orEmpty().contains("Conflicting") }
            )
        }
        org.junit.Assert.assertTrue(db.accountDao.findAll().isEmpty())
        org.junit.Assert.assertTrue(db.categoryDao.findAll().isEmpty())
        org.junit.Assert.assertTrue(db.transactionDao.findAll().isEmpty())
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

    @Test
    fun utf16UnicodeExportRoundTrip() = runBlocking {
        val json = KotlinxSerializationModule.provideJson()
        val unicode = "Кошелёк \uD834\uDD1E \uD83D\uDE80"
        val account = com.ivy.data.db.entity.AccountEntity(
            id = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
            name = unicode,
            currency = "EUR",
            color = 0,
        )
        val transaction = com.ivy.data.db.entity.TransactionEntity(
            id = java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"),
            accountId = account.id,
            type = com.ivy.base.model.TransactionType.EXPENSE,
            amount = 123456.75,
            title = "Покупка $unicode",
            description = "Точная сумма $unicode",
            dateTime = java.time.Instant.parse("2025-01-02T03:04:05Z"),
        )
        val payload = IvyWalletCompleteData(
            formatVersion = 2,
            accounts = listOf(account),
            transactions = listOf(transaction),
        )
        useCase.importJson(json.encodeToString(payload)).shouldBeSuccessful()
        val before = json.decodeFromString<IvyWalletCompleteData>(useCase.generateJsonBackup())
        org.junit.Assert.assertEquals(payload.accounts, before.accounts)
        org.junit.Assert.assertEquals(payload.transactions, before.transactions)
        val exported = tempAndroidFile("unicode-backup", ".zip")
        try {
            useCase.exportToFile(exported.toUri())
            java.util.zip.ZipFile(exported).use { zip ->
                zip.getInputStream(zip.getEntry("data.json")).use { input ->
                    org.junit.Assert.assertEquals(0xFE, input.read())
                    org.junit.Assert.assertEquals(0xFF, input.read())
                }
                val decoded = zip.getInputStream(zip.getEntry("data.json"))
                    .bufferedReader(Charsets.UTF_16).use { it.readText() }
                org.junit.Assert.assertEquals(before, json.decodeFromString<IvyWalletCompleteData>(decoded))
            }
            // Restore into an empty database, not a merge with the original rows.
            closeDb()
            createDb()
            useCase.importBackupFile(exported.toUri(), onProgress = {}).shouldBeSuccessful()
            val after = json.decodeFromString<IvyWalletCompleteData>(useCase.generateJsonBackup())
            org.junit.Assert.assertEquals(before.accounts, after.accounts)
            org.junit.Assert.assertEquals(before.transactions, after.transactions)
            org.junit.Assert.assertEquals(123456.75, after.transactions.sumOf { it.amount }, 0.0)
        } finally {
            exported.delete()
        }
    }

    @Test
    fun tombstoneExportRestoresCompleteActiveWalletIntoEmptyRoom() = runBlocking {
        val json = KotlinxSerializationModule.provideJson()
        val payload = completeRestorePayload()
        useCase.importJson(json.encodeToString(payload)).shouldBeSuccessful()
        val legacy = withDeletedTagOwners(payload)
        db.writeTagDao.save(legacy.tags.last())
        db.writeTransactionDao.save(legacy.transactions.last())
        db.writeTagAssociationDao.save(legacy.tagAssociations.drop(1))
        val original = rawBackupGraph()
        val active = json.decodeFromString<IvyWalletCompleteData>(useCase.generateJsonBackup())
        org.junit.Assert.assertEquals(3, active.omittedDeletedTagAssociations)
        org.junit.Assert.assertEquals(payload.transactions, active.transactions)
        org.junit.Assert.assertEquals(payload.tags, active.tags)
        org.junit.Assert.assertEquals(payload.tagAssociations, active.tagAssociations)
        val archive = tempAndroidFile("tombstone-backup", ".zip")
        try {
            useCase.exportToFile(archive.toUri())
            org.junit.Assert.assertEquals(original, rawBackupGraph())
            closeDb()
            createDb()
            val restored = useCase.importBackupFile(archive.toUri(), onProgress = {})
            restored.shouldBeSuccessful()
            org.junit.Assert.assertTrue(restored.warnings.any { it.startsWith("3 tag associations") })
            val after = json.decodeFromString<IvyWalletCompleteData>(useCase.generateJsonBackup())
            org.junit.Assert.assertEquals(active.copy(omittedDeletedTagAssociations = 0), after)
            val first = rawBackupGraph()
            val repeated = useCase.importBackupFile(archive.toUri(), onProgress = {})
            repeated.shouldBeSuccessful()
            org.junit.Assert.assertEquals(0, repeated.transactionsImported)
            org.junit.Assert.assertEquals(first, rawBackupGraph())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun rawTombstonesAreReportedButUnknownReferencesStillRollback() = runBlocking {
        val json = KotlinxSerializationModule.provideJson()
        val legacy = withDeletedTagOwners(completeRestorePayload())
        val result = useCase.importJson(json.encodeToString(legacy))
        result.shouldBeSuccessful()
        org.junit.Assert.assertTrue(result.warnings.any { it.startsWith("3 tag associations") })
        org.junit.Assert.assertEquals(1, db.tagAssociationDao.findAll().size)
        val before = rawBackupGraph()
        val failure = runCatching {
            useCase.importJson(json.encodeToString(legacy.copy(tags = emptyList())))
        }.exceptionOrNull()
        org.junit.Assert.assertNotNull(failure)
        org.junit.Assert.assertTrue(
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("Dangling tag association") }
        )
        org.junit.Assert.assertEquals(before, rawBackupGraph())
    }

    private fun withDeletedTagOwners(payload: IvyWalletCompleteData): IvyWalletCompleteData {
        val tag = payload.tags.single()
        val transaction = payload.transactions.single()
        val deletedTag = tag.copy(id = UUID.randomUUID(), isDeleted = true)
        val deletedTransaction = transaction.copy(id = UUID.randomUUID(), isDeleted = true)
        val association = payload.tagAssociations.single()
        return payload.copy(
            tags = payload.tags + deletedTag,
            transactions = payload.transactions + deletedTransaction,
            tagAssociations = payload.tagAssociations + listOf(
                association.copy(tagId = deletedTag.id),
                association.copy(associatedId = deletedTransaction.id),
                association.copy(tagId = deletedTag.id, associatedId = deletedTransaction.id),
            ),
        )
    }

    private suspend fun rawBackupGraph(): List<Any?> = listOf(
        db.accountDao.findAll() + db.accountDao.findAll(deleted = true),
        db.categoryDao.findAll() + db.categoryDao.findAll(deleted = true),
        db.transactionDao.findAllForRestore(),
        db.loanDao.findAllForRestore(),
        db.loanRecordDao.findAllForRestore(),
        db.plannedPaymentRuleDao.findAllForRestore(),
        db.budgetDao.findAllForRestore(),
        db.settingsDao.findAll(),
        db.tagDao.findAll(),
        db.tagAssociationDao.findAll(),
        db.exchangeRatesDao.findAllManuallyOverridden(),
        db.restorePreferencesDao.getPending(),
    )

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
