package com.ivy.data.repository

import com.ivy.base.TestDispatchersProvider
import com.ivy.base.legacy.Theme
import com.ivy.data.db.dao.fake.FakeSettingsDao
import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.model.primitive.AssetCode
import io.kotest.matchers.shouldBe
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CurrencyRepositoryTest {
    @Test
    fun `failed settings write reads USD from database`() = runTest {
        verifyFailedWrite(IllegalStateException("Write failed"))
    }

    @Test
    fun `cancelled settings write reads USD from database`() = runTest {
        verifyFailedWrite(CancellationException("Write cancelled"))
    }

    @Test
    fun `successful save invalidates mapped accounts and subsequent reads follow database`() = runTest {
        val settings = FakeSettingsDao()
        val initial = SettingsEntity(theme = Theme.AUTO, currency = "USD", bufferAmount = 0.0, name = "")
        settings.save(initial)
        val writer = mockk<WriteSettingsDao>()
        val memos = mockk<RepositoryMemoFactory>(relaxed = true)
        val repository = CurrencyRepository(settings, writer, TestDispatchersProvider, memos)
        coEvery { writer.save(any()) } coAnswers { settings.save(firstArg()) }

        repository.setBaseBaseCurrency(AssetCode.unsafe("EUR"))
        repository.getBaseCurrency() shouldBe AssetCode.unsafe("EUR")
        verify(exactly = 1) { memos.invalidateAll() }
        // Theme/name/restore and legacy settings writers do not go through this repository.
        settings.save(initial.copy(currency = "GBP"))
        repository.getBaseCurrency() shouldBe AssetCode.unsafe("GBP")
    }

    private suspend fun verifyFailedWrite(failure: Exception) {
        val settings = FakeSettingsDao()
        settings.save(SettingsEntity(theme = Theme.AUTO, currency = "USD", bufferAmount = 0.0, name = ""))
        val writer = mockk<WriteSettingsDao>()
        val repository = CurrencyRepository(settings, writer, TestDispatchersProvider, mockk(relaxed = true))
        coEvery { writer.save(any()) } returns Unit
        // The mocked successful writer leaves the database unchanged.
        repository.setBaseBaseCurrency(AssetCode.unsafe("GBP"))
        coEvery { writer.save(any()) } throws failure

        try {
            repository.setBaseBaseCurrency(AssetCode.unsafe("EUR"))
            throw AssertionError("Expected write failure")
        } catch (actual: Exception) {
            actual shouldBe failure
        }

        repository.getBaseCurrency() shouldBe AssetCode.unsafe("USD")
    }
}
