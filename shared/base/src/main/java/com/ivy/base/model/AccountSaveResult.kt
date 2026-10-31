package com.ivy.base.model

/** A returned result always means the account write was confirmed. Write failures throw. */
enum class AccountSaveResult {
    Saved,
    RefreshFailed,
}
