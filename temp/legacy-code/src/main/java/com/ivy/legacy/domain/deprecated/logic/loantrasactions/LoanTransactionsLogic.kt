package com.ivy.wallet.domain.deprecated.logic.loantrasactions

import com.ivy.base.legacy.Transaction
import com.ivy.base.model.LoanSaveResult
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.data.loan.LoanTransactionEditCommand
import com.ivy.legacy.datamodel.toEntity
import com.ivy.legacy.datamodel.temp.toLegacyDomain
import com.ivy.legacy.utils.computationThread
import java.util.UUID
import javax.inject.Inject

class LoanTransactionsLogic @Inject constructor(
    val Loan: LTLoanMapper,
    val LoanRecord: LTLoanRecordMapper,
    private val editCommand: LoanTransactionEditCommand,
) {
    suspend fun transactionSnapshot(id: UUID): Pair<TransactionEntity, Transaction>? =
        editCommand.snapshot(id)?.let { it to it.toLegacyDomain() }

    suspend fun saveTransaction(
        expected: TransactionEntity,
        transaction: Transaction,
    ): Pair<TransactionEntity, LoanSaveResult> {
        val updated = transaction.toEntity().copy(
            toAccountId = expected.toAccountId,
            toAmount = expected.toAmount,
            attachmentUrl = expected.attachmentUrl,
        )
        val result = editCommand.save(expected, updated) { updateAssociatedLoanData(transaction) }
        return updated to result
    }

    private suspend fun updateAssociatedLoanData(
        transaction: Transaction,
    ) {
        computationThread {
            requireNotNull(transaction.loanId)
            if (transaction.loanId != null && transaction.loanRecordId == null) {
                Loan.updateAssociatedLoan(
                    transaction = transaction,
                )
            } else if (transaction.loanId != null && transaction.loanRecordId != null) {
                LoanRecord.updateAssociatedLoanRecord(
                    transaction = transaction,
                )
            }
        }
    }
}
