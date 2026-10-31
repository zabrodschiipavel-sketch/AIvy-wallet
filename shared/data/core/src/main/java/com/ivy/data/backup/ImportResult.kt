package com.ivy.data.backup

import kotlinx.collections.immutable.ImmutableList

data class ImportResult(
    val rowsFound: Int,
    val transactionsImported: Int,
    val accountsImported: Int,
    val categoriesImported: Int,
    val failedRows: ImmutableList<CSVRow>,
    val transactionsSkipped: Int,
    val error: String?,
    val warnings: ImmutableList<String>,
    val preferencesPending: Boolean,
)
