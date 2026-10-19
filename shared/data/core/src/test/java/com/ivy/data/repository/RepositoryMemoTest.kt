package com.ivy.data.repository

import com.ivy.base.TestDispatchersProvider
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.model.sync.Identifiable
import com.ivy.data.model.sync.UniqueId
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

class RepositoryMemoTest {
    private data class Id(override val value: UUID = UUID.randomUUID()) : UniqueId
    private data class Item(override val id: Id = Id(), val name: String) : Identifiable<Id>

    private val observer = mockk<DataObserver>(relaxed = true)
    private val factory = RepositoryMemoFactory(observer, TestDispatchersProvider)
    private fun memo() = factory.createMemo<Item, Id>(
        getDataWriteSaveEvent = { DataWriteEvent.AllDataChange },
        getDateWriteDeleteEvent = { DataWriteEvent.AllDataChange },
    )

    @Test
    fun `failed deletes preserve cache and do not notify`() = runTest {
        val memo = memo()
        val item = Item(name = "cached")
        memo.findAll({ listOf(item) }, { toList() })

        suspend fun expectFailure(operation: suspend () -> Unit) {
            var failed = false
            try {
                operation()
            } catch (_: IllegalStateException) {
                failed = true
            }
            failed shouldBe true
            memo.items shouldBe mapOf(item.id to item)
            memo.findAllMemoized shouldBe true
        }

        expectFailure { memo.deleteById(item.id) { error("delete failed") } }
        expectFailure { memo.deleteAll { error("delete failed") } }
        coVerify(exactly = 0) { observer.post(any()) }
    }

    @Test
    fun `failed writes preserve cache and do not notify`() = runTest {
        val memo = memo()
        val item = Item(name = "cached")
        memo.findAll({ listOf(item) }, { toList() })
        val updated = item.copy(name = "updated")
        for (many in listOf(false, true)) {
            var failed = false
            try {
                if (many) memo.saveMany(listOf(updated)) { error("write failed") }
                else memo.save(updated) { error("write failed") }
            } catch (_: IllegalStateException) {
                failed = true
            }
            failed shouldBe true
            memo.items shouldBe mapOf(item.id to item)
            memo.findAllMemoized shouldBe true
        }
        coVerify(exactly = 0) { observer.post(any()) }
    }

    @Test
    fun `invalidate all clears every memo and forces reload without changing snapshots`() = runTest {
        val first = memo()
        val second = memo()
        val old = Item(name = "old")
        val fresh = old.copy(name = "fresh")
        first.findAll({ listOf(old) }, { toList() })
        second.findById(old.id) { old }
        val snapshot = first.items

        factory.invalidateAll()

        first.items shouldBe emptyMap()
        second.items shouldBe emptyMap()
        first.findAllMemoized shouldBe false
        snapshot shouldBe mapOf(old.id to old)
        first.findAll({ listOf(fresh) }, { toList() }) shouldBe listOf(fresh)
        second.findById(old.id) { fresh } shouldBe fresh
    }

    @Test
    fun `in flight find all retries after invalidate`() = runTest {
        val memo = memo()
        val old = Item(name = "old")
        val fresh = old.copy(name = "fresh")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val result = async {
            memo.findAll({
                loads++
                if (loads == 1) {
                    started.complete(Unit)
                    release.await()
                    listOf(old)
                } else listOf(fresh)
            }, { toList() })
        }
        started.await()
        factory.invalidateAll()
        release.complete(Unit)

        result.await() shouldBe listOf(fresh)
        loads shouldBe 2
        memo.items shouldBe mapOf(fresh.id to fresh)
    }

    @Test
    fun `in flight find by id retries after invalidate`() = runTest {
        val memo = memo()
        val old = Item(name = "old")
        val fresh = old.copy(name = "fresh")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val result = async {
            memo.findById(old.id) {
                loads++
                if (loads == 1) {
                    started.complete(Unit)
                    release.await()
                    old
                } else fresh
            }
        }
        started.await()
        factory.invalidateAll()
        release.complete(Unit)

        result.await() shouldBe fresh
        loads shouldBe 2
        memo.items shouldBe mapOf(fresh.id to fresh)
    }

    @Test
    fun `successful save supersedes an older in flight read`() = runTest {
        val memo = memo()
        val old = Item(name = "old")
        val fresh = old.copy(name = "fresh")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            memo.findById(old.id) {
                started.complete(Unit)
                release.await()
                old
            }
        }
        started.await()
        memo.save(fresh) { }
        release.complete(Unit)

        result.await() shouldBe fresh
        memo.items shouldBe mapOf(fresh.id to fresh)
        coVerify(exactly = 1) { observer.post(DataWriteEvent.AllDataChange) }
    }

    @Test
    fun `cached find all applies sorting`() = runTest {
        val memo = memo()
        val a = Item(name = "a")
        val b = Item(name = "b")
        memo.findAll({ listOf(b, a) }, { sortedBy { it.name } })
        memo.findAll({ error("must use cache") }, { sortedBy { it.name } }) shouldBe listOf(a, b)
    }

    @Test
    fun `write completing after invalidation cannot cache pre restore value`() = runTest {
        val memo = memo()
        val old = Item(name = "old")
        val fresh = old.copy(name = "restored")
        var database = old
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = async {
            memo.save(old) {
                database = it
                committed.complete(Unit)
                release.await()
            }
        }
        committed.await()
        factory.invalidateAll()
        database = fresh
        release.complete(Unit)
        write.await()

        memo.items shouldBe emptyMap()
        memo.findAllMemoized shouldBe false
        var reads = 0
        memo.findById(old.id) {
            reads++
            database
        } shouldBe fresh
        reads shouldBe 1
    }

    @Test
    fun `concurrent mutations serialize database writes and cache publication`() = runTest {
        val memo = memo()
        val first = Item(name = "first")
        val second = first.copy(name = "second")
        var database = first
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondAttempted = CompletableDeferred<Unit>()
        var secondEntered = false
        val firstWrite = async {
            memo.save(first) {
                database = it
                committed.complete(Unit)
                release.await()
            }
        }
        committed.await()
        val secondWrite = async {
            secondAttempted.complete(Unit)
            memo.save(second) {
                secondEntered = true
                database = it
            }
        }
        secondAttempted.await()
        // Run the second coroutine until it suspends on the mutation mutex.
        kotlinx.coroutines.yield()
        secondEntered shouldBe false
        database shouldBe first
        release.complete(Unit)
        firstWrite.await()
        secondWrite.await()

        secondEntered shouldBe true
        database shouldBe second
        memo.items shouldBe mapOf(second.id to second)
        memo.findById(second.id) { error("must use cache") } shouldBe second
    }
}
