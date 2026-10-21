package com.ivy.wallet.domain.deprecated.logic

import com.ivy.base.model.LoanSaveResult
import com.ivy.data.loan.LoanDeletionCommand
import com.ivy.data.loan.LoanWriteCommand
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanRecordData
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class LoanRecordCreator @Inject constructor(
    private val loanWrite: LoanWriteCommand,
    private val loanDeletionCommand: LoanDeletionCommand,
) {
    suspend fun create(
        loan: Loan,
        data: CreateLoanRecordData,
        writeAssociatedData: suspend (LoanRecord) -> Unit,
        onRefreshUI: suspend (LoanRecord) -> Unit
    ): LoanSaveResult {
        val item = LoanRecord(
                    loanId = loan.id,
                    note = data.note?.trim(),
                    amount = data.amount,
                    dateTime = data.dateTime,
                    isSynced = false,
                    interest = data.interest,
                    accountId = data.account?.id,
                    convertedAmount = data.convertedAmount,
                    loanRecordType = data.loanRecordType
                )

        return save(item, loan, true, writeAssociatedData, onRefreshUI)
    }

    suspend fun edit(
        updatedItem: LoanRecord,
        loan: Loan,
        writeAssociatedData: suspend (LoanRecord) -> Unit,
        onRefreshUI: suspend (LoanRecord) -> Unit
    ): LoanSaveResult = save(updatedItem, loan, false, writeAssociatedData, onRefreshUI)

    private suspend fun save(
        item: LoanRecord,
        loan: Loan,
        creating: Boolean,
        writeAssociatedData: suspend (LoanRecord) -> Unit,
        onRefreshUI: suspend (LoanRecord) -> Unit,
    ): LoanSaveResult {
        val result = loanWrite.saveRecord(item.toEntity(), creating, loan.toEntity()) {
            writeAssociatedData(item)
        }
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
        item: LoanRecord,
        onRefreshUI: suspend () -> Unit
    ) {
        loanDeletionCommand.deleteLoanRecord(item.id)
        onRefreshUI()
    }
}
