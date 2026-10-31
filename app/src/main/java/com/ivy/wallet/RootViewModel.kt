package com.ivy.wallet

import android.content.Intent
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.legacy.Theme
import com.ivy.base.legacy.stringRes
import com.ivy.base.model.TransactionType
import com.ivy.data.DataObserver
import com.ivy.data.DataWriteEvent
import com.ivy.data.backup.RestorePreferencesRecovery
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.repository.LegalRepository
import com.ivy.frp.test.TestIdlingResource
import com.ivy.legacy.IvyWalletCtx
import com.ivy.legacy.utils.ioThread
import com.ivy.legacy.utils.readOnly
import com.ivy.navigation.DisclaimerScreen
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.MainScreen
import com.ivy.navigation.Navigation
import com.ivy.navigation.OnboardingScreen
import com.ivy.ui.R
import com.ivy.wallet.domain.deprecated.logic.notification.TransactionReminderLogic
import com.ivy.wallet.migrations.MigrationsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class RootViewModel @Inject constructor(
    private val ivyContext: IvyWalletCtx,
    private val nav: Navigation,
    private val settingsDao: SettingsDao,
    private val sharedPrefs: SharedPrefs,
    private val transactionReminderLogic: TransactionReminderLogic,
    private val migrationsManager: MigrationsManager,
    private val legalRepo: LegalRepository,
    private val restorePreferencesRecovery: RestorePreferencesRecovery,
    private val dataObserver: DataObserver,
) : ViewModel() {

    companion object {
        const val EXTRA_ADD_TRANSACTION_TYPE = "add_transaction_type_extra"

        const val USER_INACTIVITY_TIME_LIMIT = 60 // Time in seconds
    }

    private var appLockEnabled = false

    private val _appLocked = MutableStateFlow<Boolean?>(null)
    val appLocked = _appLocked.readOnly()

    private val _startupError = MutableStateFlow(false)
    val startupError = _startupError.readOnly()

    private var initializationJob: Job? = null
    private var initialized = false
    private var latestSystemDarkMode = false

    private val preparationMutex = Mutex()
    private val _settingsPrepared = MutableStateFlow(0L)
    val settingsPrepared = _settingsPrepared.readOnly()

    fun start(systemDarkMode: Boolean, intent: Intent) {
        latestSystemDarkMode = systemDarkMode
        if (initializationJob?.isActive == true) return

        initializationJob = viewModelScope.launch {
            prepare {
                refreshSettings()
                if (!initialized) initialize(intent)
            }
        }
    }

    private suspend fun prepare(block: suspend () -> Unit) {
        preparationMutex.withLock {
            TestIdlingResource.increment()
            try {
                block()
                _startupError.value = false
                _settingsPrepared.value += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the existing blocking error and retry path for failed recovery.
                _startupError.value = true
            } finally {
                TestIdlingResource.decrement()
            }
        }
    }

    private suspend fun refreshSettings() {
        // No settings reads or widget refreshes may precede journal recovery.
        restorePreferencesRecovery.recoverPending()
        val savedTheme = ioThread { settingsDao.findAll().firstOrNull()?.theme }
        val startDay = ioThread { sharedPrefs.getInt(SharedPrefs.START_DATE_OF_MONTH, 1) }
        val lockEnabled = ioThread {
            sharedPrefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false)
        }
        ivyContext.switchTheme(savedTheme ?: if (latestSystemDarkMode) Theme.DARK else Theme.LIGHT)
        ivyContext.setStartDayOfMonth(startDay)
        if (initialized && lockEnabled && !appLockEnabled) {
            _appLocked.value = lockEnabled
        }
        appLockEnabled = lockEnabled
    }

    private suspend fun initialize(intent: Intent) {
        val onboarded = ioThread { isOnboardingCompleted() }
        val disclaimerAccepted = legalRepo.isDisclaimerAccepted()
        // viewModelScope runs on Main; never mutate navigation on IO.
        if (onboarded) {
            navigateOnboardedUser(intent)
        } else {
            nav.navigateTo(OnboardingScreen)
        }
        if (!disclaimerAccepted) nav.navigateTo(DisclaimerScreen)
        initialized = true
        _appLocked.value = appLockEnabled
        observeRestoredSettings()
        viewModelScope.launch {
            // executeMigrations retains its existing two-second delay.
            migrationsManager.executeMigrations()
        }
    }

    private fun observeRestoredSettings() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            dataObserver.writeEvents.collect { event ->
                if (event == DataWriteEvent.AllDataChange) {
                    prepare { refreshSettings() }
                }
            }
        }
    }

    private fun navigateOnboardedUser(intent: Intent) {
        if (!handleSpecialStart(intent)) {
            nav.navigateTo(MainScreen)
            transactionReminderLogic.scheduleReminder()
        }
    }

    @Suppress("SwallowedException")
    private fun handleSpecialStart(intent: Intent): Boolean {
        val addTrnType: TransactionType? = try {
            intent.getSerializableExtra(EXTRA_ADD_TRANSACTION_TYPE) as? TransactionType
                ?: TransactionType.valueOf(intent.getStringExtra(EXTRA_ADD_TRANSACTION_TYPE) ?: "")
        } catch (e: IllegalArgumentException) {
            null
        }

        if (addTrnType != null) {
            nav.navigateTo(
                EditTransactionScreen(
                    initialTransactionId = null,
                    type = addTrnType
                )
            )

            return true
        }

        return false
    }

    @Suppress("EmptyFunctionBlock")
    fun handleBiometricAuthResult(
        onAuthSuccess: () -> Unit = {}
    ): BiometricPrompt.AuthenticationCallback {
        return object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.d(stringRes(R.string.authentication_succeeded))
                unlockApp()
                onAuthSuccess()
            }

            override fun onAuthenticationFailed() {
                Timber.d(stringRes(R.string.authentication_failed))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            }
        }
    }

    private fun isOnboardingCompleted(): Boolean {
        return sharedPrefs.getBoolean(SharedPrefs.ONBOARDING_COMPLETED, false)
    }

    // App Lock & UserInactivity --------------------------------------------------------------------
    fun isAppLockEnabled(): Boolean {
        return appLockEnabled
    }

    fun isAppLocked(): Boolean {
        // by default we assume that the app is locked
        return appLocked.value ?: true
    }

    fun lockApp() {
        _appLocked.value = true
    }

    fun unlockApp() {
        _appLocked.value = false
    }

    private val userInactiveTime = AtomicLong(0)
    private var userInactiveJob: Job? = null

    @Suppress("MagicNumber")
    fun startUserInactiveTimeCounter() {
        if (userInactiveJob != null && userInactiveJob!!.isActive) return

        userInactiveJob = viewModelScope.launch(Dispatchers.IO) {
            while (userInactiveTime.get() < USER_INACTIVITY_TIME_LIMIT &&
                userInactiveJob != null && !userInactiveJob?.isCancelled!!
            ) {
                delay(1000)
                userInactiveTime.incrementAndGet()
            }

            if (!isAppLocked()) {
                lockApp()
            }

            cancel()
        }
    }

    fun checkUserInactiveTimeStatus() {
        if (userInactiveTime.get() < USER_INACTIVITY_TIME_LIMIT) {
            if (userInactiveJob != null && userInactiveJob?.isCancelled == false) {
                userInactiveJob?.cancel()
                resetUserInactiveTimer()
            }
        }
    }

    fun resetUserInactiveTimer() {
        userInactiveTime.set(0)
    }
}
