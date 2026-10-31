package com.ivy.base.model

/** A returned result confirms the database commit; write failures throw. */
enum class LoanSaveResult {
    Saved,
    RefreshFailed,
}
