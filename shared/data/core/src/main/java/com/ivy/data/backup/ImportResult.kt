package com.ivy.data.backup

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class ImportResult(
    val rowsFound: Int,
    val transactionsImported: Int,
    val accountsImported: Int,
    val categoriesImported: Int,
    val failedRows: ImmutableList<CSVRow>,
    val transactionsSkipped: Int = 0,
    val error: String? = null,
    val warnings: ImmutableList<String> = persistentListOf(),
    val preferencesPending: Boolean = false,
)
