package com.ivy.wallet.domain.deprecated.logic.loantrasactions

import com.ivy.base.legacy.Transaction
import com.ivy.legacy.utils.computationThread
import javax.inject.Inject

data class LoanTransactionsLogic @Inject constructor(
    val Loan: LTLoanMapper,
    val LoanRecord: LTLoanRecordMapper
) {
    suspend fun updateAssociatedLoanData(
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
