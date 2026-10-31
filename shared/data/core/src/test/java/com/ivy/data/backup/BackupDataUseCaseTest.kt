package com.ivy.data.backup

import com.ivy.base.TestDispatchersProvider
import com.ivy.base.di.KotlinxSerializationModule
import com.ivy.base.legacy.SharedPrefs
import com.ivy.data.DataObserver
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.dao.fake.FakeAccountDao
import com.ivy.data.db.dao.fake.FakeBudgetDao
import com.ivy.data.db.dao.fake.FakeCategoryDao
import com.ivy.data.db.dao.fake.FakeLoanDao
import com.ivy.data.db.dao.fake.FakeLoanRecordDao
import com.ivy.data.db.dao.fake.FakePlannedPaymentDao
import com.ivy.data.db.dao.fake.FakeSettingsDao
import com.ivy.data.db.dao.fake.FakeTagAssociationDao
import com.ivy.data.db.dao.fake.FakeTagDao
import com.ivy.data.db.dao.fake.FakeTransactionDao
import com.ivy.data.repository.fake.fakeRepositoryMemoFactory
import com.ivy.data.testResource
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackupDataUseCaseTest {
    private fun newBackupDataUseCase(
        accountDao: FakeAccountDao = FakeAccountDao(),
        categoryDao: FakeCategoryDao = FakeCategoryDao(),
        transactionDao: FakeTransactionDao = FakeTransactionDao(),
        plannedPaymentDao: FakePlannedPaymentDao = FakePlannedPaymentDao(),
        budgetDao: FakeBudgetDao = FakeBudgetDao(),
        settingsDao: FakeSettingsDao = FakeSettingsDao(),
        loanDao: FakeLoanDao = FakeLoanDao(),
        loanRecordDao: FakeLoanRecordDao = FakeLoanRecordDao(),
        tagDao: FakeTagDao = FakeTagDao(),
        tagAssociationDao: FakeTagAssociationDao = FakeTagAssociationDao(),
        afterTransaction: suspend () -> Unit = {},
        prefs: SharedPrefs = mockk(relaxed = true),
        memoFactory: com.ivy.data.repository.RepositoryMemoFactory = fakeRepositoryMemoFactory(),
        pendingAfterWrite: Boolean = false,
        observer: DataObserver = DataObserver(),
    ): BackupDataUseCase {
        val database = mockk<IvyRoomDatabase>(relaxed = true)
        every { database.writeAccountDao } returns accountDao
        coEvery { database.exchangeRatesDao.findAllManuallyOverridden() } returns emptyList()
        coEvery { database.restorePreferencesDao.getPending() } returns null
        val runner = mockk<BackupTransactionRunner>()
        coEvery { runner.inTransaction<Any?>(any()) } coAnswers {
            val result = firstArg<suspend () -> Any?>().invoke()
            afterTransaction()
            result
        }
        if (pendingAfterWrite) {
            coEvery { database.restorePreferencesDao.getPending() } returnsMany listOf(
                null, RestorePreferencesEntity(id = 1, json = "{}")
            )
        }
        return BackupDataUseCase(
            accountDao = accountDao,
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            loanRecordDao = loanRecordDao,
            loanDao = loanDao,
            plannedPaymentRuleDao = plannedPaymentDao,
            transactionDao = transactionDao,
            transactionWriter = transactionDao,
            settingsDao = settingsDao,
            categoryWriter = categoryDao,
            settingsWriter = settingsDao,
            budgetWriter = budgetDao,
            loanWriter = loanDao,
            loanRecordWriter = loanRecordDao,
            plannedPaymentRuleWriter = plannedPaymentDao,

            context = mockk(relaxed = true),
            sharedPrefs = prefs,
            json = KotlinxSerializationModule.provideJson(),
            dispatchersProvider = TestDispatchersProvider,
            dataObserver = observer,
            tagsReader = tagDao,
            tagsWriter = tagDao,
            tagAssociationReader = tagAssociationDao,
            tagAssociationWriter = tagAssociationDao,
            database = database,
            transactionRunner = runner,
            recovery = RestorePreferencesRecovery(
                database,
                prefs,
                KotlinxSerializationModule.provideJson(),
                TestDispatchersProvider
            ),
            memoFactory = memoFactory,
        )
    }

    @Test
    fun cancellationAtTransactionReturnStillInvalidatesCaches() = runTest {
        val memo = mockk<com.ivy.data.repository.RepositoryMemoFactory>(relaxed = true)
        val observer = mockk<DataObserver>(relaxed = true)
        val useCase = newBackupDataUseCase(
            memoFactory = memo,
            observer = observer,
            afterTransaction = { throw kotlinx.coroutines.CancellationException("after commit") },
        )
        val progress = mutableListOf<Double>()
        try {
            useCase.importJson("{}", onProgress = { progress += it })
            error("Expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            e.message shouldBe "after commit"
        }
        io.mockk.verify(exactly = 1) { memo.invalidateAll() }
        (1.0 in progress) shouldBe false
        io.mockk.coVerify(exactly = 0) { observer.post(any()) }
    }

    @Test
    fun cancelledCommittedImportReplaysPreferencesAndNotifiesOnce() = runTest {
        val prefs = mockk<SharedPrefs>(relaxed = true)
        every { prefs.commitBooleans(any()) } returns true
        val observer = mockk<DataObserver>(relaxed = true)
        val memo = mockk<com.ivy.data.repository.RepositoryMemoFactory>(relaxed = true)
        val useCase = newBackupDataUseCase(
            prefs = prefs,
            observer = observer,
            memoFactory = memo,
            pendingAfterWrite = true,
            afterTransaction = { throw kotlinx.coroutines.CancellationException("after commit") },
        )
        try {
            useCase.importJson("{}")
            error("Expected cancellation")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            expected.message shouldBe "after commit"
        }
        io.mockk.verify(exactly = 1) { prefs.commitBooleans(emptyMap()) }
        io.mockk.verify(exactly = 1) { memo.invalidateAll() }
        io.mockk.coVerify(exactly = 1) { observer.post(com.ivy.data.DataWriteEvent.AllDataChange) }
    }

    @Test
    fun preferenceRecoveryFailureReturnsCommittedCountsAndFinalProgress() = runTest {
        val prefs = mockk<SharedPrefs>(relaxed = true)
        every { prefs.commitBooleans(any()) } returns false
        val observer = mockk<DataObserver>(relaxed = true)
        val useCase = newBackupDataUseCase(
            prefs = prefs,
            pendingAfterWrite = true,
            observer = observer,
        )
        val progress = mutableListOf<Double>()
        val result = useCase.importJson(
            testResource("backups/450-150.json").readText(Charsets.UTF_16),
            onProgress = { progress += it },
        )
        result.accountsImported shouldBeGreaterThan 0
        result.transactionsImported shouldBeGreaterThan 0
        result.preferencesPending shouldBe true
        (result.error != null) shouldBe true
        progress.last() shouldBe 1.0
        io.mockk.verify(exactly = 1) { prefs.commitBooleans(any()) }
        io.mockk.coVerify(exactly = 1) { observer.post(com.ivy.data.DataWriteEvent.AllDataChange) }
    }

    private suspend fun backupTestCase(backupVersion: String) {
        // given
        val originalBackupUseCase = newBackupDataUseCase()
        val backupJsonData = testResource("backups/$backupVersion.json")
            .readText(Charsets.UTF_16)

        // when
        val importedDataRes = originalBackupUseCase.importJson(backupJsonData, onProgress = {})

        // then
        importedDataRes.accountsImported shouldBeGreaterThan 0
        importedDataRes.transactionsImported shouldBeGreaterThan 0
        importedDataRes.categoriesImported shouldBeGreaterThan 0
        importedDataRes.failedRows.size shouldBe 0

        // Also - exporting and re-importing the data should work
        // given
        val exportedJson = originalBackupUseCase.generateJsonBackup()

        // when
        val freshBackupUseCase = newBackupDataUseCase()
        val reImportedDataRes = freshBackupUseCase.importJson(exportedJson, onProgress = {})
        // then
        reImportedDataRes shouldBe importedDataRes.copy(warnings = kotlinx.collections.immutable.persistentListOf())

        // Finally, exporting again should yield the same result
        freshBackupUseCase.generateJsonBackup() shouldBe exportedJson
    }

    @Test
    fun `backup compatibility with 450 (150)`() = runTest {
        backupTestCase("450-150")
    }
}
