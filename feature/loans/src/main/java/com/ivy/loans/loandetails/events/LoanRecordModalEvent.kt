package com.ivy.loans.loandetails.events

import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.loans.loan.data.DisplayLoanRecord

sealed interface LoanRecordModalEvent : LoanDetailsScreenEvent {
    data class OnClickLoanRecord(val displayLoanRecord: DisplayLoanRecord) : LoanRecordModalEvent
    data class OnDeleteLoanRecord(val loanRecord: LoanRecord) : LoanRecordModalEvent
    data object OnDismissLoanRecord : LoanRecordModalEvent

    data object OnChangeDate : LoanRecordModalEvent
    data object OnChangeTime : LoanRecordModalEvent
}
