package com.ivy.wallet

import android.content.Intent
import androidx.lifecycle.ViewModelStore
import com.ivy.base.legacy.SharedPrefs
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.backup.RestorePreferencesRecovery
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.repository.LegalRepository
import com.ivy.legacy.IvyWalletCtx
import com.ivy.navigation.MainScreen
import com.ivy.navigation.Navigation
import com.ivy.wallet.domain.deprecated.logic.notification.TransactionReminderLogic
import com.ivy.wallet.migrations.MigrationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {
    private val context = mockk<IvyWalletCtx>(relaxed = true)
    private val navigation = mockk<Navigation>(relaxed = true)
    private val settings = mockk<SettingsDao>()
    private val prefs = mockk<SharedPrefs>()
    private val reminders = mockk<TransactionReminderLogic>(relaxed = true)
    private val migrations = mockk<MigrationsManager>(relaxed = true)
    private val legal = mockk<LegalRepository>()
    private val recovery = mockk<RestorePreferencesRecovery>()
    private val observer = DataObserver()
    private val intent = mockk<Intent>(relaxed = true)
    private val store = ViewModelStore()
    private lateinit var model: RootViewModel
    private var lockEnabled = false

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { recovery.recoverPending() } returns Unit
        coEvery { settings.findAll() } returns emptyList()
        coEvery { legal.isDisclaimerAccepted() } returns true
        every { prefs.getInt(SharedPrefs.START_DATE_OF_MONTH, 1) } returns 1
        every { prefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false) } answers { lockEnabled }
        every { prefs.getBoolean(SharedPrefs.ONBOARDING_COMPLETED, false) } returns true
        every { intent.getStringExtra(any()) } returns null
        every { intent.getSerializableExtra(any()) } returns null
        model = RootViewModel(
            context, navigation, settings, prefs, reminders, migrations, legal, recovery, observer
        )
        store.put("root", model)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `restored enabling locks the current session after recovery`() = runTest {
        startAndAwait()
        model.isAppLocked() shouldBe false
        coEvery { recovery.recoverPending() } answers { lockEnabled = true }

        observer.post(DataWriteEvent.AllDataChange)
        model.settingsPrepared.first { it == 2L }

        model.isAppLockEnabled() shouldBe true
        model.isAppLocked() shouldBe true
        coVerify(exactly = 2) { recovery.recoverPending() }
        verify(exactly = 1) { navigation.navigateTo(MainScreen) }
    }

    @Test
    fun `unchanged enabled lock does not relock authenticated session`() = runTest {
        lockEnabled = true
        startAndAwait()
        model.unlockApp()

        observer.post(DataWriteEvent.AllDataChange)
        model.settingsPrepared.first { it == 2L }

        model.isAppLockEnabled() shouldBe true
        model.isAppLocked() shouldBe false
    }

    @Test
    fun `restore recovery failure blocks settings reads and supports retry`() = runTest {
        startAndAwait()
        coEvery { recovery.recoverPending() } throws IllegalStateException("pending")

        observer.post(DataWriteEvent.AllDataChange)
        model.startupError.first { it }

        model.settingsPrepared.value shouldBe 1L
        coVerify(exactly = 1) { settings.findAll() }
        verify(exactly = 1) { prefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false) }
        coEvery { recovery.recoverPending() } answers { lockEnabled = true }
        model.start(false, intent)
        model.settingsPrepared.first { it == 2L }
        model.startupError.value shouldBe false
        model.isAppLocked() shouldBe true
        verify(exactly = 1) { navigation.navigateTo(MainScreen) }
        runCurrent()
        coVerify(exactly = 1) { migrations.executeMigrations() }
    }

    @Test
    fun `repeated start prepares settings without duplicate routes or migrations`() = runTest {
        model.start(false, intent)
        model.start(false, intent)
        model.settingsPrepared.first { it == 1L }
        model.start(true, intent)
        model.settingsPrepared.first { it == 2L }

        verify(exactly = 1) { navigation.navigateTo(any()) }
        verify(exactly = 1) { reminders.scheduleReminder() }
        runCurrent()
        coVerify(exactly = 1) { migrations.executeMigrations() }
        model.isAppLocked() shouldBe false
    }

    @Test
    fun `startup recovery failure reads no settings`() = runTest {
        coEvery { recovery.recoverPending() } throws IllegalStateException("pending")
        model.start(false, intent)
        model.startupError.first { it }
        coVerify(exactly = 0) { settings.findAll() }
        verify(exactly = 0) { prefs.getBoolean(any(), any()) }
        verify(exactly = 0) { navigation.navigateTo(any()) }
        model.settingsPrepared.value shouldBe 0L
    }

    @Test
    fun `cancellation propagates without reporting a recovery error`() = runTest {
        coEvery { recovery.recoverPending() } throws CancellationException("cancelled")
        model.start(false, intent)
        runCurrent()

        model.startupError.value shouldBe false
        model.settingsPrepared.value shouldBe 0L
        coVerify(exactly = 0) { settings.findAll() }
        verify(exactly = 0) { navigation.navigateTo(any()) }
    }

    private suspend fun startAndAwait() {
        model.start(false, intent)
        model.settingsPrepared.first { it == 1L }
    }
}
