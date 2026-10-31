package com.ivy.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTextReaderTest {
    @Test
    fun readsLegacyUtf16WithByteOrderMark() {
        val text = "{\"name\":\"Кошелёк\"}"
        val bytes = text.toByteArray(Charsets.UTF_16)
        assertEquals(text, readBackupText(bytes.inputStream(), maxBytes = bytes.size.toLong()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsActualBytesAboveLimit() {
        readBackupText(ByteArray(16).inputStream(), maxBytes = 4)
    }
}
