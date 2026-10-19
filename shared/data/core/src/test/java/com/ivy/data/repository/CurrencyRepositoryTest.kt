package com.ivy.data.repository

import com.ivy.base.TestDispatchersProvider
import com.ivy.base.legacy.Theme
import com.ivy.data.db.dao.fake.FakeSettingsDao
import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.model.primitive.AssetCode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CurrencyRepositoryTest {
    @Test
    fun `failed settings write clears previous memo and reads USD from database`() = runTest {
        verifyFailedWrite(IllegalStateException("Write failed"))
    }

    @Test
    fun `cancelled settings write clears previous memo and reads USD from database`() = runTest {
        verifyFailedWrite(CancellationException("Write cancelled"))
    }

    private suspend fun verifyFailedWrite(failure: Exception) {
        val settings = FakeSettingsDao()
        settings.save(SettingsEntity(theme = Theme.AUTO, currency = "USD", bufferAmount = 0.0, name = ""))
        val writer = mockk<WriteSettingsDao>()
        val repository = CurrencyRepository(settings, writer, TestDispatchersProvider)
        coEvery { writer.save(any()) } returns Unit
        // Populate the memo, then simulate a DB value changed outside this repository.
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
