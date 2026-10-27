package com.ivy.transaction

import com.ivy.legacy.datamodel.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class EditorAccountSelectionTest {
    private val archived = Account(name = "Archived", color = 0, archived = true)
    private val first = Account(name = "Cash", color = 0, archived = false)
    private val second = Account(name = "Card", color = 0, archived = false)
    private val all = listOf(archived, first, second)

    @Test
    fun newDraftUsesActiveAccountsInTheirOriginalOrder() {
        assertEquals(listOf(first, second), editorAccounts(all, editing = false, isArchived = Account::archived))
        assertEquals(
            emptyList<Account>(),
            editorAccounts(listOf(archived), editing = false, isArchived = Account::archived)
        )
    }

    @Test
    fun historicalEditingKeepsArchivedAccountReferences() {
        assertEquals(all, editorAccounts(all, editing = true, isArchived = Account::archived))
    }

    @Test
    fun validExplicitAccountWinsOverLastUsed() {
        assertEquals(second.id, initialAccountId(listOf(first.id, second.id), second.id, first.id))
    }

    @Test
    fun staleOrArchivedExplicitAccountFallsBackToAnAvailablePreference() {
        val available = editorAccounts(all, editing = false, isArchived = Account::archived).map(Account::id)
        assertEquals(second.id, initialAccountId(available, UUID.randomUUID(), second.id))
        assertEquals(second.id, initialAccountId(available, archived.id, second.id))
    }

    @Test
    fun staleOrArchivedPreferencesFallBackToTheFirstActiveAccount() {
        val available = editorAccounts(all, editing = false, isArchived = Account::archived).map(Account::id)
        assertEquals(first.id, initialAccountId(available, null, UUID.randomUUID()))
        assertEquals(first.id, initialAccountId(available, null, archived.id))
        assertEquals(first.id, initialAccountId(available, UUID.randomUUID(), UUID.randomUUID()))
    }

    @Test
    fun missingHintsUseTheFirstAvailableAccount() {
        assertEquals(first.id, initialAccountId(listOf(first.id, second.id), null, null))
    }

    @Test
    fun emptyCatalogCannotSelectAStaleRequestedAccount() {
        assertNull(initialAccountId(emptyList(), first.id, second.id))
        assertNull(initialAccountId(emptyList(), null, null))
    }
}
