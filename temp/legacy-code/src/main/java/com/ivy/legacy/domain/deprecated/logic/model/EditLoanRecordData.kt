package com.ivy.wallet.domain.deprecated.logic.model

import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.legacy.datamodel.Loan

data class EditLoanRecordData(
    val loan: Loan,
    val newLoanRecord: LoanRecord,
    val originalLoanRecord: LoanRecord,
    val createLoanRecordTransaction: Boolean = false,
    val reCalculateLoanAmount: Boolean = false
)
