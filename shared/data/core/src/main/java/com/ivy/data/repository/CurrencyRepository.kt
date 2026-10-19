package com.ivy.data.repository

import android.icu.util.Currency
import com.ivy.base.legacy.Theme
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.model.primitive.AssetCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyRepository @Inject constructor(
    private val settingsDao: SettingsDao,
    private val writeSettingsDao: WriteSettingsDao,
    private val dispatchersProvider: DispatchersProvider,
    private val memoFactory: RepositoryMemoFactory,
) {
    companion object {
        const val FALLBACK_DEFAULT_CURRENCY = "USD"
    }

    private val setterMutex = Mutex()

    // Settings also have legacy/restore writers. Read the source of truth rather than
    // keep a separate memo they cannot invalidate. Frozen accounts avoid this lookup
    // in AccountMapper; the remaining lookup runs on IO.
    suspend fun getBaseCurrency(): AssetCode = withContext(dispatchersProvider.io) {
        val currencyCode = settingsDao.findFirstOrNull()?.currency
            ?: getDefaultFIATCurrency()?.currencyCode
        currencyCode?.let(AssetCode::from)?.getOrNull()
            ?: AssetCode.unsafe(FALLBACK_DEFAULT_CURRENCY)
    }

    private fun getDefaultFIATCurrency(): Currency? {
        return Currency.getInstance(Locale.getDefault())
    }

    suspend fun setBaseBaseCurrency(newCurrency: AssetCode) {
        setterMutex.withLock {
            try {
                withContext(dispatchersProvider.io) {
                    val currentEntity = settingsDao.findFirstOrNull()
                        ?: SettingsEntity(
                            theme = Theme.AUTO,
                            currency = FALLBACK_DEFAULT_CURRENCY,
                            bufferAmount = 0.0,
                            name = "",
                            isSynced = true,
                            isDeleted = false,
                            id = UUID.randomUUID()
                        )
                    writeSettingsDao.save(currentEntity.copy(currency = newCurrency.code))
                    // SQL also freezes inherited account currencies; discard mapped accounts.
                    memoFactory.invalidateAll()
                }
            } catch (error: Throwable) {
                // A cancelled Room call may already have committed.
                memoFactory.invalidateAll()
                throw error
            }
        }
    }
}
