package com.ivy.loans.loandetails.events

sealed interface LoanModalEvent : LoanDetailsScreenEvent {
    data object OnDismissLoanModal : LoanModalEvent
    data object PerformCalculation : LoanModalEvent
    data object OnChangeDate : LoanModalEvent
    data object OnChangeTime : LoanModalEvent
}
