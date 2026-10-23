package com.ivy.data.backup

import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
import com.ivy.data.db.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BackupDeletedTagAssociationsTest {
    private val account = AccountEntity(name = "Cash", currency = "USD", color = 0)
    private val transaction = TransactionEntity(
        accountId = account.id, type = TransactionType.INCOME, amount = 12.0, dateTime = Instant.EPOCH,
    )
    private val tag = TagEntity(
        id = UUID.randomUUID(), name = "Keep", description = null, color = 0, icon = null,
        orderNum = 0.0, dateTime = Instant.EPOCH, isDeleted = false, lastSyncedTime = Instant.EPOCH,
    )
    private val deletedTag = tag.copy(id = UUID.randomUUID(), isDeleted = true)
    private val deletedTransaction = transaction.copy(id = UUID.randomUUID(), isDeleted = true)
    private fun link(tagId: UUID, ownerId: UUID) = TagAssociationEntity(tagId, ownerId, Instant.EPOCH, false)
    private val validLink = link(tag.id, transaction.id)
    private val incoming = IvyWalletCompleteData(
        formatVersion = 2,
        accounts = listOf(account),
        transactions = listOf(transaction, deletedTransaction),
        tags = listOf(tag, deletedTag),
        tagAssociations = listOf(
            validLink, link(deletedTag.id, transaction.id), link(tag.id, deletedTransaction.id),
            link(deletedTag.id, deletedTransaction.id),
        ),
    )

    @Test
    fun knownDeletedOwnersDoNotInvalidateActiveWallet() {
        val plan = BackupMergePlanner.plan(incoming, IvyWalletCompleteData(formatVersion = 2))
        assertEquals(listOf(tag), plan.additions.tags)
        assertEquals(listOf(transaction), plan.additions.transactions)
        assertEquals(listOf(validLink), plan.additions.tagAssociations)
    }
}
