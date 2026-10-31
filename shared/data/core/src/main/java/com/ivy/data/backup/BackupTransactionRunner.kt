package com.ivy.data.backup

import androidx.room.withTransaction
import com.ivy.data.db.IvyRoomDatabase
import javax.inject.Inject

/** Injectable boundary for unit tests; production always uses a real Room transaction. */
open class BackupTransactionRunner @Inject constructor(private val database: IvyRoomDatabase) {
    open suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
