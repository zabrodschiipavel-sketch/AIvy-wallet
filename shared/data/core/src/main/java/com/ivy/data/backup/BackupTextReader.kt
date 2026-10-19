package com.ivy.data.backup

import com.ivy.base.legacy.DEFAULT_MAX_UNZIP_ENTRY_BYTES
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Applies the same byte ceiling to standalone legacy JSON and extracted JSON. */
internal fun readBackupText(
    input: InputStream,
    maxBytes: Long = DEFAULT_MAX_UNZIP_ENTRY_BYTES,
): String {
    require(maxBytes > 0) { "Backup size limit must be positive" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        bytesRead += read
        require(bytesRead <= maxBytes) { "Backup JSON exceeds size limit" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray().toString(Charsets.UTF_16)
}
