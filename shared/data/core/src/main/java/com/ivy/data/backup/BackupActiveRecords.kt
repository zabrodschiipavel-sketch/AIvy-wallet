package com.ivy.data.backup

internal fun IvyWalletCompleteData.activeRecords(): IvyWalletCompleteData {
    require(omittedDeletedTagAssociations >= 0) { "Invalid omitted tag association count" }
    val deletedTagIds = tags.filter { it.isDeleted }.map { it.id }.toSet()
    val activeTagIds = tags.filter { !it.isDeleted }.map { it.id }.toSet()
    require(deletedTagIds.intersect(activeTagIds).isEmpty()) { "Conflicting tag deletion states" }
    val deletedTransactionIds = transactions.filter { it.isDeleted }.map { it.id }.toSet()
    val activeTransactionIds = transactions.filter { !it.isDeleted }.map { it.id }.toSet()
    require(deletedTransactionIds.intersect(activeTransactionIds).isEmpty()) {
        "Conflicting transaction deletion states"
    }
    val liveLinks = tagAssociations.filter { !it.isDeleted }
    val newOmitted = liveLinks.count { it.tagId in deletedTagIds || it.associatedId in deletedTransactionIds }
    return copy(
        accounts = accounts.filter { !it.isDeleted },
        budgets = budgets.filter { !it.isDeleted },
        categories = categories.filter { !it.isDeleted },
        loanRecords = loanRecords.filter { !it.isDeleted },
        loans = loans.filter { !it.isDeleted },
        plannedPaymentRules = plannedPaymentRules.filter { !it.isDeleted },
        settings = settings.filter { !it.isDeleted },
        transactions = transactions.filter { !it.isDeleted },
        tags = tags.filter { !it.isDeleted },
        tagAssociations = liveLinks.filter { it.tagId !in deletedTagIds && it.associatedId !in deletedTransactionIds },
        omittedDeletedTagAssociations = Math.addExact(omittedDeletedTagAssociations, newOmitted),
    )
}
