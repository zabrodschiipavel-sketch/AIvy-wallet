package com.ivy.data.repository

import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.DeleteOperation
import com.ivy.data.model.sync.Identifiable
import com.ivy.data.model.sync.UniqueId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepositoryMemoFactory @Inject constructor(
    private val dataObserver: DataObserver,
    private val dispatchers: DispatchersProvider,
) {
    private val lock = Any()
    private val memos = mutableListOf<WeakReference<RepositoryMemo<*, *>>>()

    fun <T : Identifiable<TID>, TID : UniqueId> createMemo(
        getDataWriteSaveEvent: (List<T>) -> DataWriteEvent,
        getDateWriteDeleteEvent: (DeleteOperation<TID>) -> DataWriteEvent
    ): RepositoryMemo<T, TID> = synchronized(lock) {
        val memo = RepositoryMemo(
            dataObserver = dataObserver,
            dispatchers = dispatchers,
            getDataWriteSaveEvent = getDataWriteSaveEvent,
            getDataWriteDeleteEvent = getDateWriteDeleteEvent,
        )
        memos.removeAll { it.get() == null }
        memos.add(WeakReference(memo))
        memo
    }

    fun invalidateAll(): Unit = synchronized(lock) {
        memos.removeAll { reference ->
            val memo = reference.get()
            memo?.invalidate()
            memo == null
        }
        Unit
    }
}

class RepositoryMemo<T : Identifiable<TID>, TID : UniqueId> internal constructor(
    private val dataObserver: DataObserver,
    private val dispatchers: DispatchersProvider,
    private val getDataWriteSaveEvent: (List<T>) -> DataWriteEvent,
    private val getDataWriteDeleteEvent: (DeleteOperation<TID>) -> DataWriteEvent,
) {
    private val lock = Any()
    private val mutationMutex = Mutex()
    private val memo = mutableMapOf<TID, T>()
    private var generation = 0L
    private var allMemoized = false

    val items: Map<TID, T>
        get() = synchronized(lock) { memo.toMap() }

    val findAllMemoized: Boolean
        get() = synchronized(lock) { allMemoized }

    fun invalidate(): Unit = synchronized(lock) {
        generation++
        memo.clear()
        allMemoized = false
    }

    suspend fun findAll(
        findAllOperation: suspend () -> List<T>,
        sortMemo: Collection<T>.() -> List<T>,
    ): List<T> {
        while (true) {
            val token = synchronized(lock) {
                if (allMemoized) return sortMemo(memo.values.toList())
                generation
            }
            // Never hold a JVM monitor across suspend I/O.
            val loaded = withContext(dispatchers.io) { findAllOperation() }
            synchronized(lock) {
                if (token == generation) {
                    memo.clear()
                    loaded.forEach { memo[it.id] = it }
                    allMemoized = true
                    return loaded
                }
            }
            // An invalidation or successful mutation superseded this load.
        }
    }

    suspend fun findById(
        id: TID,
        findByIdOperation: suspend (TID) -> T?
    ): T? {
        while (true) {
            val token = synchronized(lock) {
                memo[id]?.let { return it }
                generation
            }
            val loaded = withContext(dispatchers.io) { findByIdOperation(id) }
            synchronized(lock) {
                if (token == generation) {
                    loaded?.let { memo[it.id] = it }
                    return loaded
                }
            }
        }
    }

    suspend fun findByIds(
        ids: List<TID>,
        findByIdOperation: suspend (TID) -> T?
    ): List<T> = ids.mapNotNull { id -> findById(id, findByIdOperation) }

    // Serialize database mutations and cache publication, without a JVM monitor over I/O.
    private suspend fun mutate(
        operation: suspend () -> Unit,
        updateCache: () -> Unit,
        event: () -> DataWriteEvent,
    ) {
        withContext(dispatchers.io) {
            mutationMutex.withLock {
                val token = synchronized(lock) { generation }
                try {
                    operation()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    // The database may have committed before cancellation was delivered.
                    invalidate()
                    throw cancelled
                }
                synchronized(lock) {
                    val unchanged = token == generation
                    generation++
                    if (unchanged) {
                        updateCache()
                    } else {
                        // Invalidation may have restored data while this write was suspended.
                        memo.clear()
                        allMemoized = false
                    }
                }
            }
            dataObserver.post(event())
        }
    }

    suspend fun save(
        value: T,
        writeOperation: suspend (T) -> Unit,
    ) {
        mutate(
            operation = { writeOperation(value) },
            updateCache = { memo[value.id] = value },
            event = { getDataWriteSaveEvent(listOf(value)) },
        )
    }

    suspend fun saveMany(
        values: List<T>,
        writeOperation: suspend (List<T>) -> Unit,
    ) {
        mutate(
            operation = { writeOperation(values) },
            updateCache = { values.forEach { memo[it.id] = it } },
            event = { getDataWriteSaveEvent(values) },
        )
    }

    suspend fun deleteById(
        id: TID,
        deleteByIdOperation: suspend (TID) -> Unit,
    ) {
        mutate(
            operation = { deleteByIdOperation(id) },
            updateCache = { memo.remove(id) },
            event = { getDataWriteDeleteEvent(DeleteOperation.Just(listOf(id))) },
        )
    }

    suspend fun deleteAll(
        deleteAllOperation: suspend () -> Unit,
    ) {
        mutate(
            operation = deleteAllOperation,
            updateCache = {
                memo.clear()
                allMemoized = false
            },
            event = { getDataWriteDeleteEvent(DeleteOperation.All) },
        )
    }
}
