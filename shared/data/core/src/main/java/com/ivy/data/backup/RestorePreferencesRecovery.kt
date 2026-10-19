package com.ivy.data.backup

import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.IvyRoomDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Run before loading app settings on startup. Replaying a committed journal is idempotent. */
@Singleton
class RestorePreferencesRecovery @Inject constructor(
    private val database: IvyRoomDatabase,
    private val sharedPrefs: SharedPrefs,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) {
    private val mutex = Mutex()

    suspend fun recoverPending() = withRestoreLock { recoverPendingLocked() }

    internal suspend fun <T> withRestoreLock(block: suspend () -> T): T =
        withContext(dispatchers.io) { mutex.withLock { block() } }

    internal suspend fun recoverPendingLocked() {
        val pending = database.restorePreferencesDao.getPending() ?: return
        val values = json.decodeFromString<Map<String, Boolean>>(pending.json)
        require(values.keys.all { it in RestorePreferenceKeys }) { "Unsupported restore preference" }
        check(sharedPrefs.commitBooleans(values)) { "Could not persist restored settings; recovery is pending" }
        database.restorePreferencesDao.delete()
    }
}

internal val RestorePreferenceKeys = setOf(
    SharedPrefs.SHOW_NOTIFICATIONS,
    SharedPrefs.APP_LOCK_ENABLED,
    SharedPrefs.HIDE_CURRENT_BALANCE,
    SharedPrefs.TRANSFERS_AS_INCOME_EXPENSE,
)
