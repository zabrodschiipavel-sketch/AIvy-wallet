package com.ivy.wallet.domain.deprecated.logic

import com.ivy.base.model.LoanSaveResult
import com.ivy.data.loan.LoanDeletionCommand
import com.ivy.data.loan.LoanWriteCommand
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanRecordData
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

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

        return save(item, loan, null, writeAssociatedData, onRefreshUI)
    }

    suspend fun edit(
        originalItem: LoanRecord,
        updatedItem: LoanRecord,
        loan: Loan,
        writeAssociatedData: suspend (LoanRecord) -> Unit,
        onRefreshUI: suspend (LoanRecord) -> Unit
    ): LoanSaveResult = save(updatedItem, loan, originalItem, writeAssociatedData, onRefreshUI)

    private suspend fun save(
        item: LoanRecord,
        loan: Loan,
        expected: LoanRecord?,
        writeAssociatedData: suspend (LoanRecord) -> Unit,
        onRefreshUI: suspend (LoanRecord) -> Unit,
    ): LoanSaveResult {
        val result = loanWrite.saveRecord(item.toEntity(), expected?.toEntity(), loan.toEntity()) {
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
