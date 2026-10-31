package com.ivy.data.backup

import com.ivy.base.model.TransactionType
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.TagAssociationEntity
import com.ivy.data.db.entity.TagEntity
import com.ivy.data.db.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BackupDeletedTagAssociationsTest {
    private val account = AccountEntity(name = "Cash", currency = "USD", color = 0)
    private val transaction = TransactionEntity(
        accountId = account.id,
        type = TransactionType.INCOME,
        amount = 12.0,
        dateTime = Instant.EPOCH,
    )
    private val tag = TagEntity(
        id = UUID.randomUUID(),
        name = "Keep",
        description = null,
        color = 0,
        icon = null,
        orderNum = 0.0,
        dateTime = Instant.EPOCH,
        isDeleted = false,
        lastSyncedTime = Instant.EPOCH,
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
            validLink,
            link(deletedTag.id, transaction.id),
            link(tag.id, deletedTransaction.id),
            link(deletedTag.id, deletedTransaction.id),
        ),
    )

    @Test
    fun knownDeletedOwnersDoNotInvalidateActiveWallet() {
        val plan = BackupMergePlanner.plan(incoming, IvyWalletCompleteData(formatVersion = 2))
        assertEquals(listOf(tag), plan.additions.tags)
        assertEquals(listOf(transaction), plan.additions.transactions)
        assertEquals(listOf(validLink), plan.additions.tagAssociations)
        assertTrue(plan.warnings.any { it.startsWith("3 tag associations") })
    }

    @Test
    fun missingTagRemainsAnError() {
        expectFailure("Dangling tag association", incoming.copy(tags = listOf(tag)))
    }

    @Test
    fun missingTransactionRemainsAnError() {
        expectFailure("Dangling tag association", incoming.copy(transactions = listOf(transaction)))
    }

    @Test
    fun existingTombstoneCannotBeInferredAsIncomingDeletion() {
        expectFailure(
            "Dangling tag association",
            incoming.copy(
                tags = listOf(tag),
                transactions = listOf(transaction),
                tagAssociations = listOf(link(deletedTag.id, transaction.id)),
            ),
            IvyWalletCompleteData(formatVersion = 2, tags = listOf(deletedTag)),
        )
    }

    @Test
    fun contradictoryTagStatesAreRejected() {
        expectFailure("Conflicting tag deletion states", incoming.copy(tags = listOf(tag, tag.copy(isDeleted = true))))
    }

    @Test
    fun contradictoryTransactionStatesAreRejected() {
        expectFailure(
            "Conflicting transaction deletion states",
            incoming.copy(transactions = listOf(transaction, transaction.copy(isDeleted = true))),
        )
    }

    @Test
    fun deletedAssociationItselfIsNotCountedAgain() {
        val plan = BackupMergePlanner.plan(
            incoming.copy(tagAssociations = listOf(link(tag.id, deletedTransaction.id).copy(isDeleted = true))),
            IvyWalletCompleteData(formatVersion = 2),
        )
        assertTrue(plan.additions.tagAssociations.isEmpty())
        assertTrue(plan.warnings.none { it.contains("tag associations") })
    }

    @Test
    fun exportedCountSurvivesImportAsAWarning() {
        val plan = BackupMergePlanner.plan(
            incoming.activeRecords(),
            IvyWalletCompleteData(formatVersion = 2),
        )
        assertTrue(plan.warnings.any { it.startsWith("3 tag associations") })
    }

    @Test
    fun counterCannotLegitimizeUnknownReferences() {
        expectFailure(
            "Dangling tag association",
            incoming.copy(tags = listOf(tag), omittedDeletedTagAssociations = 3),
        )
    }

    @Test(expected = ArithmeticException::class)
    fun omittedCounterCannotOverflow() {
        incoming.copy(omittedDeletedTagAssociations = Int.MAX_VALUE).activeRecords()
    }

    @Test
    fun activeSelectionIsIdempotent() {
        val active = incoming.activeRecords()
        assertEquals(3, active.omittedDeletedTagAssociations)
        assertEquals(active, active.activeRecords())
    }

    @Test
    fun deletedAssociationIdentityCannotBeResurrected() {
        expectFailure(
            "resurrection is forbidden",
            incoming.copy(tagAssociations = listOf(validLink)),
            IvyWalletCompleteData(formatVersion = 2, tagAssociations = listOf(validLink.copy(isDeleted = true))),
        )
    }

    private fun expectFailure(
        message: String,
        value: IvyWalletCompleteData,
        current: IvyWalletCompleteData = IvyWalletCompleteData(formatVersion = 2),
    ) {
        try {
            BackupMergePlanner.plan(value, current)
            throw AssertionError("Expected $message")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message))
        }
    }
}
