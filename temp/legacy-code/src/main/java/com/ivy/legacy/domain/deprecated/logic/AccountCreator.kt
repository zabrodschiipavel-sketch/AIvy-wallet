package com.ivy.legacy.domain.deprecated.logic

import androidx.compose.ui.graphics.toArgb
import arrow.core.getOrElse
import arrow.core.raise.either
import com.ivy.data.account.AccountBalanceWriteCommand
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.IconAsset
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.CurrencyRepository
import com.ivy.legacy.utils.ioThread
import com.ivy.wallet.domain.deprecated.logic.WalletAccountLogic
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
import com.ivy.wallet.domain.pure.util.nextOrderNum
import java.util.UUID
import javax.inject.Inject
import com.ivy.legacy.datamodel.Account as LegacyAccount
import kotlin.coroutines.cancellation.CancellationException

class AccountCreator @Inject constructor(
    private val accountLogic: WalletAccountLogic,
    private val accountDao: AccountDao,
    private val accountWrite: AccountBalanceWriteCommand,
    private val currencyRepository: CurrencyRepository,
) {
    suspend fun createAccount(
        data: CreateAccountData,
        onRefreshUI: suspend () -> Unit
    ): AccountSaveResult {
        ioThread {
            require(data.balance.isFinite()) { "Balance must be finite" }
            accountWrite.save(account = {
                either {
                    Account(
                        id = AccountId(UUID.randomUUID()),
                        name = NotBlankTrimmedString.from(data.name).bind(),
                        asset = AssetCode.from(data.currency).bind(),
                        color = ColorInt(data.color.toArgb()),
                        icon = data.icon?.let { IconAsset.from(it).bind() },
                        includeInBalance = data.includeBalance,
                        orderNum = accountDao.findMaxOrderNum().nextOrderNum(),
                        archived = false,
                    )
                }.getOrElse { throw IllegalArgumentException(it) }
            }, adjustment = { account ->
                accountLogic.balanceAdjustment(account, 0.0, data.balance)
            })
        }

        return runRefresh(onRefreshUI)
    }

    suspend fun editAccount(
        legacyAccount: LegacyAccount,
        newBalance: Double,
        onRefreshUI: suspend () -> Unit
    ): AccountSaveResult {
        val updatedLegacyAccount = legacyAccount.copy(
            isSynced = false
        )
        ioThread {
            require(newBalance.isFinite()) { "Balance must be finite" }
            require(!legacyAccount.isDeleted) { "Cannot save a deleted account" }
            legacyAccount.icon?.let {
                IconAsset.from(it).getOrElse { error -> throw IllegalArgumentException(error) }
            }
            accountWrite.save(account = {
                updatedLegacyAccount.toDomainAccount(currencyRepository)
                    .getOrElse { throw IllegalArgumentException(it) }
            }, adjustment = { account ->
                accountLogic.balanceAdjustment(
                    account,
                    accountLogic.calculateAccountBalance(updatedLegacyAccount),
                    newBalance,
                )
            })
        }

        return runRefresh(onRefreshUI)
    }

    private suspend fun runRefresh(onRefreshUI: suspend () -> Unit): AccountSaveResult {
        try {
            onRefreshUI()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AccountSaveResult.RefreshFailed
        }
        return AccountSaveResult.Saved
    }
}
