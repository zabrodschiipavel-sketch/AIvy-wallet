package com.ivy.wallet.domain.deprecated.logic

import androidx.compose.ui.graphics.toArgb
import com.ivy.base.model.LoanSaveResult
import com.ivy.data.db.dao.read.LoanDao
import com.ivy.data.loan.LoanDeletionCommand
import com.ivy.data.loan.LoanWriteCommand
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.utils.ioThread
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanData
import com.ivy.wallet.domain.pure.util.nextOrderNum
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class LoanCreator @Inject constructor(
    private val dao: LoanDao,
    private val loanWrite: LoanWriteCommand,
    private val loanDeletionCommand: LoanDeletionCommand,
) {
    suspend fun create(
        data: CreateLoanData,
        writeAssociatedData: suspend (Loan) -> Unit,
        onRefreshUI: suspend (Loan) -> Unit
    ): LoanSaveResult {
        val item = ioThread {
            Loan(
                name = data.name.trim(),
                amount = data.amount,
                type = data.type,
                color = data.color.toArgb(),
                icon = data.icon,
                note = data.note,
                orderNum = dao.findMaxOrderNum().nextOrderNum(),
                isSynced = false,
                accountId = data.account?.id,
                dateTime = data.dateTime
            )
        }
        return save(item, creating = true, writeAssociatedData, onRefreshUI)
    }

    suspend fun edit(
        updatedItem: Loan,
        writeAssociatedData: suspend (Loan) -> Unit,
        onRefreshUI: suspend (Loan) -> Unit
    ): LoanSaveResult = save(updatedItem, false, writeAssociatedData, onRefreshUI)

    private suspend fun save(
        item: Loan,
        creating: Boolean,
        writeAssociatedData: suspend (Loan) -> Unit,
        onRefreshUI: suspend (Loan) -> Unit,
    ): LoanSaveResult {
        val result = loanWrite.saveLoan(item.toEntity(), creating) { writeAssociatedData(item) }
        try {
            onRefreshUI(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LoanSaveResult.RefreshFailed
        }
        return result
    }

    suspend fun delete(
        item: Loan,
        onRefreshUI: suspend () -> Unit
    ) {
        loanDeletionCommand.deleteLoan(item.id)
        onRefreshUI()
    }
}
