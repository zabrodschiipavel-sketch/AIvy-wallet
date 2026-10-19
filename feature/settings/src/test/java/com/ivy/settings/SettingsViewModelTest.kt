package com.ivy.settings

import androidx.compose.runtime.MutableState
import com.ivy.base.Toaster
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.repository.CurrencyRepository
import com.ivy.wallet.domain.action.account.AccountsAct
import com.ivy.wallet.domain.action.viewmodel.home.UpdateAccCacheAct
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
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
    private val accounts = mockk<AccountsAct>()
    private val cache = mockk<UpdateAccCacheAct>()
    private val observer = mockk<DataObserver>(relaxed = true)
    private val toaster = mockk<Toaster>(relaxed = true)

    private fun viewModel() = SettingsViewModel(
        settingsDao = mockk(), ivyContext = mockk(), logoutLogic = mockk(),
        sharedPrefs = mockk(), backupDataUseCase = mockk(), startDayOfMonthAct = mockk(),
        updateStartDayOfMonthAct = mockk(), syncExchangeRatesUseCase = mockk(relaxed = true),
        settingsAct = mockk(), updateSettingsAct = mockk(), settingsWriter = mockk(),
        currencyRepository = repository, accountsAct = accounts, updateAccCacheAct = cache,
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
            coEvery { accounts(Unit) } returns persistentListOf()
            coEvery { cache(any()) } coAnswers {
                currency(vm) shouldBe ""
                firstArg()
            }
            vm = viewModel()
            vm.onEvent(SettingsEvent.SetCurrency("EUR"))
            runCurrent()
            currency(vm) shouldBe ""
            coVerify(exactly = 0) { accounts(Unit) }
            saved.complete(Unit)
            runCurrent()
            currency(vm) shouldBe "EUR"
            coVerifyOrder {
                repository.setBaseBaseCurrency(AssetCode.unsafe("EUR"))
                accounts(Unit)
                cache(any())
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
            coVerify(exactly = 0) { accounts(Unit) }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
