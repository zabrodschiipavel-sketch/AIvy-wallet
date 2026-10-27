package com.ivy.transaction

import java.util.UUID

internal fun <T> editorAccounts(
    accounts: List<T>,
    editing: Boolean,
    isArchived: (T) -> Boolean,
): List<T> = if (editing) accounts else accounts.filterNot(isArchived)

internal fun initialAccountId(
    accounts: List<UUID>,
    requested: UUID?,
    lastSelected: UUID?,
): UUID? = requested?.takeIf { it in accounts }
    ?: lastSelected?.takeIf { it in accounts }
    ?: accounts.firstOrNull()
