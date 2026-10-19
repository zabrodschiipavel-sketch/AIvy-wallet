package com.ivy.data.backup

import android.content.Context
import android.net.Uri
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

private const val ZipSignatureFirstByte = 0x50
private const val ZipSignatureSecondByte = 0x4b

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

    private suspend fun snapshot(forRestore: Boolean = false): IvyWalletCompleteData = IvyWalletCompleteData(
        formatVersion = 2,
        accounts = accountDao.findAll() + if (forRestore) accountDao.findAll(deleted = true) else emptyList(),
        budgets = if (forRestore) budgetDao.findAllForRestore() else budgetDao.findAll().filterNot { it.isDeleted },
        categories = categoryDao.findAll() + if (forRestore) categoryDao.findAll(deleted = true) else emptyList(),
        loanRecords = if (forRestore) {
            loanRecordDao.findAllForRestore()
        } else {
            loanRecordDao.findAll().filterNot { it.isDeleted }
        },
        loans = if (forRestore) loanDao.findAllForRestore() else loanDao.findAll().filterNot { it.isDeleted },
        plannedPaymentRules = if (forRestore) {
            plannedPaymentRuleDao.findAllForRestore()
        } else {
            plannedPaymentRuleDao.findAll().filterNot { it.isDeleted }
        },
        settings = settingsDao.findAll().filter { forRestore || !it.isDeleted },
        transactions = if (forRestore) {
            transactionDao.findAllForRestore()
        } else {
            transactionDao.findAll().filterNot { it.isDeleted }
        },
        sharedPrefs = getSharedPrefsData(),
        tags = tagsReader.findAll().filter { forRestore || !it.isDeleted },
        tagAssociations = tagAssociationReader.findAll().filter { forRestore || !it.isDeleted },
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
            val source = context.contentResolver.openInputStream(backupFileUri)
                ?: error("Could not open backup")
            val text = source.buffered().use { input ->
                input.mark(2)
                val archive = input.read() == ZipSignatureFirstByte && input.read() == ZipSignatureSecondByte
                input.reset()
                if (archive) {
                    // Failed archive validation must never fall through to an unbounded JSON read.
                    unzip(input, dir)
                    val files = dir.listFiles().orEmpty().filter {
                        it.isFile && it.extension.equals("json", ignoreCase = true)
                    }
                    check(files.size == 1) { "Expected exactly one JSON backup file" }
                    files.single().inputStream().use { readBackupText(it) }
                } else {
                    readBackupText(input)
                }
            }
            onProgress(0.1)
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
        var committed = false
        try {
            recovery.withRestoreLock {
                // Never overwrite a previously committed but unrecovered journal.
                recovery.recoverPendingLocked()
                val plan = try {
                    transactionRunner.inTransaction {
                        val plan = BackupMergePlanner.plan(incoming, snapshot(forRestore = true))
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
                    }.also { committed = true }
                } catch (failure: Exception) {
                    // The runner may throw after Room has committed. The old journal was
                    // drained under this same lock, so a pending journal proves our commit.
                    withContext(NonCancellable) {
                        try {
                            committed = database.restorePreferencesDao.getPending() != null
                            if (committed) recovery.recoverPendingLocked()
                        } catch (cleanupFailure: Exception) {
                            failure.addSuppressed(cleanupFailure)
                        }
                    }
                    throw failure
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
        } finally {
            // Observers may re-enter backup/recovery: never notify under the restore mutex.
            if (committed) {
                withContext(NonCancellable) {
                    dataObserver.post(DataWriteEvent.AllDataChange)
                }
            }
        }
    }
}
