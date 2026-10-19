package com.ivy.base.legacy

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MODE_WRITE = "w"
private const val MODE_READ = "r"

const val DEFAULT_MAX_UNZIP_ENTRIES = 32
const val DEFAULT_MAX_UNZIP_ENTRY_BYTES = 128L * 1024L * 1024L
const val DEFAULT_MAX_UNZIP_TOTAL_BYTES = 256L * 1024L * 1024L

data class UnzipLimits(
    val maxEntries: Int = DEFAULT_MAX_UNZIP_ENTRIES,
    val maxEntryBytes: Long = DEFAULT_MAX_UNZIP_ENTRY_BYTES,
    val maxTotalBytes: Long = DEFAULT_MAX_UNZIP_TOTAL_BYTES
)

fun zip(context: Context, zipFile: Uri, files: List<File>) {
    context.contentResolver.openFileDescriptor(zipFile, MODE_WRITE).use { descriptor ->
        val target = descriptor?.fileDescriptor ?: throw IOException("Cannot open backup for writing")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { outStream ->
            zip(outStream, files)
        }
    }
}

private fun zip(
    outStream: ZipOutputStream,
    files: List<File>,
    includeParentFolder: Boolean = false
) {
    files.forEach { file ->
        if (file.isDirectory) {
            file.mkdir()
            zip(outStream, file.listFiles()?.toList() ?: emptyList(), includeParentFolder = true)
        } else {
            val fileLoc: String =
                if (file.parent.isNullOrEmpty() || !includeParentFolder) {
                    file.name
                } else {
                    (file.parent!!).substring(
                        file.parent!!.lastIndexOf("/")
                    ) + "/" + file.name
                }

            outStream.putNextEntry(ZipEntry(fileLoc))
            BufferedInputStream(FileInputStream(file)).use { inStream ->
                inStream.copyTo(outStream)
            }
        }
    }
}

fun unzip(context: Context, zipFile: Uri, location: File) {
    context.contentResolver.openFileDescriptor(zipFile, MODE_READ).use { descriptor ->
        val fd = descriptor?.fileDescriptor
            ?: throw IllegalArgumentException("Cannot open zip for reading: missing file descriptor.")
        unzip(FileInputStream(fd), location)
    }
}

fun unzip(fileInputStream: FileInputStream, location: File) {
    unzip(fileInputStream as InputStream, location, UnzipLimits())
}

fun unzip(inputStream: InputStream, location: File, limits: UnzipLimits = UnzipLimits()) {
    require(limits.maxEntries > 0) { "maxEntries must be positive." }
    require(limits.maxEntryBytes > 0) { "maxEntryBytes must be positive." }
    require(limits.maxTotalBytes > 0) { "maxTotalBytes must be positive." }

    ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
        val destinationDir = prepareEmptyDestination(location)
        var entryCount = 0
        var totalBytes = 0L
        var entry: ZipEntry?
        try {
            while (zipInputStream.nextEntry.also { entry = it } != null) {
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw IllegalArgumentException(
                        "Zip archive exceeds entry limit of ${limits.maxEntries}."
                    )
                }
                val outputFile = resolveSecureOutputFile(destinationDir, entry!!.name)
                if (entry!!.isDirectory) {
                    check(outputFile.isDirectory || outputFile.mkdirs()) { "Could not create archive directory" }
                    // closeEntry() drains unread bytes. Directory entries must not bypass byte limits.
                    require(zipInputStream.read() == -1) { "Archive directory contains file data" }
                } else {
                    val parentDir = outputFile.parentFile
                    if (parentDir != null && !parentDir.isDirectory) parentDir.mkdirs()
                    if (outputFile.exists()) {
                        throw IllegalArgumentException(
                            "Zip entry '${entry!!.name}' would overwrite an existing file."
                        )
                    }
                    totalBytes = copyBounded(zipInputStream, outputFile, entry!!.name, limits, totalBytes)
                }
                zipInputStream.closeEntry()
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IOException) {
            throw IllegalArgumentException("Malformed zip archive: ${e.message}", e)
        }
        if (entryCount == 0) {
            throw IllegalArgumentException("Malformed zip archive: no entries found.")
        }
    }
}

private fun prepareEmptyDestination(location: File): File {
    val canonicalDestination = try {
        location.canonicalFile
    } catch (e: IOException) {
        throw IllegalArgumentException("Invalid unzip destination: ${e.message}", e)
    }
    if (canonicalDestination.exists()) {
        if (!canonicalDestination.isDirectory) {
            throw IllegalStateException("Location file must be directory or not exist")
        }
        val existing = canonicalDestination.listFiles()
        if (existing == null || existing.isNotEmpty()) {
            throw IllegalStateException("Location directory must be empty")
        }
    } else {
        check(canonicalDestination.mkdirs()) { "Could not create unzip destination" }
    }
    return canonicalDestination
}

private fun resolveSecureOutputFile(destinationDir: File, entryName: String): File {
    if (entryName.isEmpty()) {
        throw IllegalArgumentException("Zip entry has an empty name.")
    }
    if (entryName.contains('\\')) {
        throw IllegalArgumentException("Zip entry '$entryName' contains a backslash.")
    }
    if (entryName.startsWith("/")) {
        throw IllegalArgumentException("Zip entry '$entryName' is an absolute path.")
    }
    if (entryName.length >= 2 && entryName[1] == ':' && entryName[0].isLetter()) {
        throw IllegalArgumentException("Zip entry '$entryName' contains a Windows drive letter.")
    }
    val outputFile = File(destinationDir, entryName)
    val destinationPrefix = destinationDir.canonicalPath + File.separator
    val outputCanonicalPath = try {
        outputFile.canonicalPath
    } catch (e: IOException) {
        throw IllegalArgumentException("Zip entry '$entryName' cannot be resolved.", e)
    }
    if (!outputCanonicalPath.startsWith(destinationPrefix)) {
        throw IllegalArgumentException("Zip entry '$entryName' escapes the destination directory.")
    }
    return File(outputCanonicalPath)
}

private fun copyBounded(
    zipInputStream: ZipInputStream,
    outputFile: File,
    entryName: String,
    limits: UnzipLimits,
    totalBytesBefore: Long
): Long {
    var entryBytes = 0L
    var totalBytes = totalBytesBefore
    try {
        BufferedOutputStream(FileOutputStream(outputFile)).use { outStream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (zipInputStream.read(buffer).also { read = it } != -1) {
                entryBytes += read
                totalBytes += read
                if (entryBytes > limits.maxEntryBytes) {
                    throw IllegalArgumentException(
                        "Zip entry '$entryName' exceeds per-file limit of ${limits.maxEntryBytes} bytes."
                    )
                }
                if (totalBytes > limits.maxTotalBytes) {
                    throw IllegalArgumentException(
                        "Zip archive exceeds total limit of ${limits.maxTotalBytes} bytes."
                    )
                }
                outStream.write(buffer, 0, read)
            }
        }
    } catch (e: IllegalArgumentException) {
        outputFile.delete()
        throw e
    }
    return totalBytes
}
