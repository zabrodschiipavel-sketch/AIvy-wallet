package com.ivy.data.backup

import com.ivy.base.di.KotlinxSerializationModule
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupMetadataTest {
    @Test
    fun legacyJsonDefaultsToVersion1AndZeroOmitted() {
        val data = KotlinxSerializationModule.provideJson()
            .decodeFromString<IvyWalletCompleteData>("{}")
        assertEquals(1, data.formatVersion)
        assertEquals(0, data.omittedDeletedTagAssociations)
    }

    @Test
    fun roundTripPreservesVersion2AndOmittedCount() {
        val json = KotlinxSerializationModule.provideJson()
        val original = IvyWalletCompleteData(
            formatVersion = 2,
            omittedDeletedTagAssociations = 3
        )
        val restored = json.decodeFromString<IvyWalletCompleteData>(
            json.encodeToString(original)
        )
        assertEquals(2, restored.formatVersion)
        assertEquals(3, restored.omittedDeletedTagAssociations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeOmittedCountIsRejected() {
        BackupMergePlanner.plan(
            IvyWalletCompleteData(
                formatVersion = 2,
                omittedDeletedTagAssociations = -1
            ),
            IvyWalletCompleteData(formatVersion = 2)
        )
    }
}
