package com.ivy.legacy.domain.action.settings

import com.ivy.data.db.dao.write.WriteSettingsDao
import com.ivy.frp.action.FPAction
import com.ivy.legacy.datamodel.Settings
import com.ivy.wallet.domain.action.account.AccountsAct
import com.ivy.wallet.domain.action.viewmodel.home.UpdateAccCacheAct
import javax.inject.Inject

class UpdateSettingsAct @Inject constructor(
    private val writeSettingsDao: WriteSettingsDao,
    private val accountsAct: AccountsAct,
    private val updateAccCacheAct: UpdateAccCacheAct,
) : FPAction<Settings, Settings>() {
    suspend fun refreshAccountCache() {
        updateAccCacheAct(accountsAct(Unit))
    }

    override suspend fun Settings.compose(): suspend () -> Settings = suspend {
        writeSettingsDao.save(this.toEntity())
        this
    }
}
