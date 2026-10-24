package ivy.compose.reports

import java.io.File
import java.security.MessageDigest

private val ExcludedFolders = setOf("build", "outputs", ".git", ".gradle", ".idea", ".kotlin", ".codegraph")
private val InputExtensions = setOf("kt", "kts", "java", "xml", "properties", "toml")

// Shared by the Gradle producer and the standalone checker. Generated build
// outputs and ordinary report files cannot change the source fingerprint.
fun composeReportFingerprint(root: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    root.walkTopDown()
        .onEnter { it.name !in ExcludedFolders }
        .filter { it.isFile && it.extension in InputExtensions }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .forEach { file ->
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.inputStream().use { stream ->
                val content = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count = stream.read(buffer)
                while (count >= 0) {
                    content.update(buffer, 0, count)
                    count = stream.read(buffer)
                }
                content.digest()
            })
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun composeFileSha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
