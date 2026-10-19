package com.ivy.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.legacy.unzip
import com.ivy.base.legacy.zip
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.db.dao.read.BudgetDao
import com.ivy.data.db.dao.read.CategoryDao
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.read.PlannedPaymentRuleDao
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.dao.read.TagAssociationDao
import com.ivy.data.db.dao.read.TagDao
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WriteBudgetDao
import com.ivy.data.db.dao.write.WriteCategoryDao
import com.ivy.data.db.dao.write.WriteLoanDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.dao.write.WritePlannedPaymentRuleDao
import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.data.db.dao.write.WriteTagAssociationDao
import com.ivy.data.db.dao.write.WriteTagDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import com.ivy.data.file.FileSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject

class BackupDataUseCase @Inject constructor(
    private val accountDao: AccountDao,
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val loanRecordDao: LoanRecordDao,
    private val loanDao: LoanDao,
    private val plannedPaymentRuleDao: PlannedPaymentRuleDao,
    private val settingsDao: SettingsDao,
    private val transactionDao: TransactionDao,
    private val transactionWriter: WriteTransactionDao,
    private val sharedPrefs: SharedPrefs,
    private val categoryWriter: WriteCategoryDao,
    private val settingsWriter: WriteSettingsDao,
    private val budgetWriter: WriteBudgetDao,
    private val loanWriter: WriteLoanDao,
    private val loanRecordWriter: WriteLoanRecordDao,
    private val plannedPaymentRuleWriter: WritePlannedPaymentRuleDao,
    @ApplicationContext
    private val context: Context,
    private val json: Json,
    private val dispatchersProvider: DispatchersProvider,
    private val fileSystem: FileSystem,
    private val dataObserver: DataObserver,
    private val tagsReader: TagDao,
    private val tagAssociationReader: TagAssociationDao,
    private val tagsWriter: WriteTagDao,
    private val tagAssociationWriter: WriteTagAssociationDao,
    private val database: com.ivy.data.db.IvyRoomDatabase,
    private val transactionRunner: BackupTransactionRunner,
    private val recovery: RestorePreferencesRecovery,
    private val memoFactory: com.ivy.data.repository.RepositoryMemoFactory,
) {
    suspend fun exportToFile(zipFileUri: Uri) {
        val dir = ownedTempDirectory()
        try {
            val file = File(dir, "data.json")
            file.writeText(generateJsonBackup(), Charsets.UTF_16)
            zip(context = context, zipFileUri, listOf(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ownedTempDirectory(): File =
        File(context.cacheDir, "backup-${UUID.randomUUID()}").also {
            check(it.mkdir()) { "Could not create backup directory" }
        }

    private suspend fun snapshot(): IvyWalletCompleteData = IvyWalletCompleteData(
        formatVersion = 2,
        accounts = accountDao.findAll(),
        budgets = budgetDao.findAll(),
        categories = categoryDao.findAll(),
        loanRecords = loanRecordDao.findAll(),
        loans = loanDao.findAll(),
        plannedPaymentRules = plannedPaymentRuleDao.findAll(),
        settings = settingsDao.findAll(),
        transactions = transactionDao.findAll(),
        sharedPrefs = getSharedPrefsData(),
        tags = tagsReader.findAll(),
        tagAssociations = tagAssociationReader.findAll(),
        manualExchangeRates = database.exchangeRatesDao.findAllManuallyOverridden(),
    )

    suspend fun generateJsonBackup(): String = withContext(dispatchersProvider.io) {
        recovery.withRestoreLock {
            recovery.recoverPendingLocked()
            json.encodeToString(transactionRunner.inTransaction { snapshot() })
        }
    }

    private fun getSharedPrefsData(): HashMap<String, String> = HashMap(
        RestorePreferenceKeys.associateWith { key ->
            sharedPrefs.getBoolean(key, key == SharedPrefs.SHOW_NOTIFICATIONS).toString()
        }
    )

    suspend fun importBackupFile(
        backupFileUri: Uri,
        onProgress: suspend (progressPercent: Double) -> Unit
    ): ImportResult = withContext(dispatchersProvider.io) {
        val dir = ownedTempDirectory()
        try {
            val text = try {
                unzip(context, backupFileUri, dir)
                onProgress(0.1)
                val files = dir.listFiles().orEmpty().filter {
                    it.isFile && it.extension.equals("json", ignoreCase = true)
                }
                check(files.size == 1) { "Expected exactly one JSON backup file" }
                fileSystem.read(files.single().toUri(), Charsets.UTF_16).getOrNull()
                    ?: error("Could not read extracted backup")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Legacy fixtures and standalone JSON backups are UTF-16.
                fileSystem.read(backupFileUri, Charsets.UTF_16).getOrNull()
                    ?: error("Could not read backup as archive or UTF-16 JSON: ${e.message}")
            }
            importJson(text, onProgress)
        } finally {
            dir.deleteRecursively()
        }
    }

    suspend fun importJson(
        jsonString: String,
        onProgress: suspend (Double) -> Unit = {},
    ): ImportResult = withContext(dispatchersProvider.io) {
        val incoming = json.decodeFromString<IvyWalletCompleteData>(jsonString)
        onProgress(0.4)
        recovery.withRestoreLock {
            // Never overwrite a previously committed but unrecovered journal.
            recovery.recoverPendingLocked()
            val plan = try {
                transactionRunner.inTransaction {
                    val plan = BackupMergePlanner.plan(incoming, snapshot())
                    val data = plan.additions
                    database.writeAccountDao.saveMany(data.accounts)
                    categoryWriter.saveMany(data.categories)
                    plannedPaymentRuleWriter.saveMany(data.plannedPaymentRules)
                    loanWriter.saveMany(data.loans)
                    loanRecordWriter.saveMany(data.loanRecords)
                    budgetWriter.saveMany(data.budgets)
                    transactionWriter.saveMany(data.transactions)
                    tagsWriter.save(data.tags)
                    tagAssociationWriter.save(data.tagAssociations)
                    settingsWriter.saveMany(data.settings)
                    database.writeExchangeRatesDao.saveMany(data.manualExchangeRates)
                    database.restorePreferencesDao.save(
                        RestorePreferencesEntity(id = 1, json = json.encodeToString(plan.preferences))
                    )
                    plan
                }
            } finally {
                // Room can commit before cancellation is delivered to its caller.
                // Clearing after a rollback is harmless; retaining stale values is not.
                memoFactory.invalidateAll()
            }
            // Only post-commit work is noncancellable, never the DB transaction.
            withContext(NonCancellable) {
                var preferenceError: String? = null
                try {
                    recovery.recoverPendingLocked()
                } catch (e: Exception) {
                    preferenceError = e.message ?: "Preference recovery failed"
                } finally {
                    dataObserver.post(DataWriteEvent.AllDataChange)
                }
                onProgress(1.0)
                ImportResult(
                    rowsFound = incoming.transactions.size,
                    transactionsImported = plan.additions.transactions.size,
                    accountsImported = plan.additions.accounts.size,
                    categoriesImported = plan.additions.categories.size,
                    failedRows = persistentListOf(),
                    transactionsSkipped = plan.skippedTransactions,
                    warnings = plan.warnings.toPersistentList(),
                    error = preferenceError,
                    preferencesPending = preferenceError != null,
                )
            }
        }
    }
}
