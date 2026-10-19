package com.ivy.settings

import androidx.compose.runtime.MutableState
import com.ivy.base.Toaster
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.repository.CurrencyRepository
import com.ivy.legacy.domain.action.settings.UpdateSettingsAct
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repository = mockk<CurrencyRepository>()
    private val settings = mockk<UpdateSettingsAct>()
    private val observer = mockk<DataObserver>(relaxed = true)
    private val toaster = mockk<Toaster>(relaxed = true)

    private fun viewModel() = SettingsViewModel(
        settingsDao = mockk(), ivyContext = mockk(), logoutLogic = mockk(),
        sharedPrefs = mockk(), backupDataUseCase = mockk(), startDayOfMonthAct = mockk(),
        updateStartDayOfMonthAct = mockk(), syncExchangeRatesUseCase = mockk(relaxed = true),
        settingsAct = mockk(), updateSettingsAct = settings, settingsWriter = mockk(),
        currencyRepository = repository,
        dataObserver = observer, toaster = toaster, exportCsvUseCase = mockk(), context = mockk()
    )

    private fun currency(vm: SettingsViewModel): Any? {
        val field = SettingsViewModel::class.java.getDeclaredField("currencyCode")
        field.isAccessible = true
        return (field.get(vm) as MutableState<*>).value
    }

    @Test
    fun `publish only after commit and legacy account reload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val saved = CompletableDeferred<Unit>()
            coEvery { repository.setBaseBaseCurrency(any()) } coAnswers { saved.await() }
            coEvery { settings.refreshAccountCache() } coAnswers {
                currency(vm) shouldBe ""
                Unit
            }
            vm = viewModel()
            vm.onEvent(SettingsEvent.SetCurrency("EUR"))
            runCurrent()
            currency(vm) shouldBe ""
            coVerify(exactly = 0) { settings.refreshAccountCache() }
            saved.complete(Unit)
            runCurrent()
            currency(vm) shouldBe "EUR"
            coVerifyOrder {
                repository.setBaseBaseCurrency(AssetCode.unsafe("EUR"))
                settings.refreshAccountCache()
                observer.post(DataWriteEvent.AllDataChange)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private lateinit var vm: SettingsViewModel

    @Test
    fun `failed save keeps displayed currency and reports error while cancellation is silent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            vm = viewModel()
            coEvery { repository.setBaseBaseCurrency(any()) } throws IllegalStateException("Save failed")
            vm.onEvent(SettingsEvent.SetCurrency("EUR"))
            runCurrent()
            currency(vm) shouldBe ""
            coVerify { toaster.show("Save failed") }
            coEvery { repository.setBaseBaseCurrency(any()) } throws CancellationException("Cancelled")
            vm.onEvent(SettingsEvent.SetCurrency("GBP"))
            runCurrent()
            currency(vm) shouldBe ""
            coVerify(exactly = 1) { toaster.show(any<String>(), any()) }
            coVerify(exactly = 0) { observer.post(any()) }
            coVerify(exactly = 0) { settings.refreshAccountCache() }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
