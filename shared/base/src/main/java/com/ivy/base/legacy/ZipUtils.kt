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

const val DefaultMaxUnzipEntries = 32
const val DefaultMaxUnzipEntryBytes = 128L * 1024L * 1024L
const val DefaultMaxUnzipTotalBytes = 256L * 1024L * 1024L

class UnzipLimits(
    val maxEntries: Int = DefaultMaxUnzipEntries,
    val maxEntryBytes: Long = DefaultMaxUnzipEntryBytes,
    val maxTotalBytes: Long = DefaultMaxUnzipTotalBytes
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
        val fd = requireNotNull(descriptor?.fileDescriptor) {
            "Cannot open zip for reading: missing file descriptor."
        }
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
        while (zipInputStream.nextEntry.also { entry = it } != null) {
            entryCount++
            require(entryCount <= limits.maxEntries) {
                "Zip archive exceeds entry limit of ${limits.maxEntries}."
            }
            totalBytes = extractEntry(zipInputStream, destinationDir, entry!!, limits, totalBytes)
            zipInputStream.closeEntry()
        }
        require(entryCount > 0) { "Malformed zip archive: no entries found." }
    }
}

private fun extractEntry(
    zipInputStream: ZipInputStream,
    destinationDir: File,
    entry: ZipEntry,
    limits: UnzipLimits,
    totalBytes: Long
): Long {
    val outputFile = resolveSecureOutputFile(destinationDir, entry.name)
    if (entry.isDirectory) {
        check(outputFile.isDirectory || outputFile.mkdirs()) { "Could not create archive directory" }
        // closeEntry() drains unread bytes. Directory entries must not bypass byte limits.
        require(zipInputStream.read() == -1) { "Archive directory contains file data" }
        return totalBytes
    }
    val parentDir = outputFile.parentFile
    if (parentDir != null && !parentDir.isDirectory) parentDir.mkdirs()
    require(!outputFile.exists()) {
        "Zip entry '${entry.name}' would overwrite an existing file."
    }
    return copyBounded(zipInputStream, outputFile, entry.name, limits, totalBytes)
}

private fun prepareEmptyDestination(location: File): File {
    val canonicalDestination = location.canonicalFile
    if (canonicalDestination.exists()) {
        check(canonicalDestination.isDirectory) { "Location file must be directory or not exist" }
        val existing = canonicalDestination.listFiles()
        check(existing != null && existing.isEmpty()) { "Location directory must be empty" }
    } else {
        check(canonicalDestination.mkdirs()) { "Could not create unzip destination" }
    }
    return canonicalDestination
}

private fun resolveSecureOutputFile(destinationDir: File, entryName: String): File {
    require(entryName.isNotEmpty()) { "Zip entry has an empty name." }
    require(!entryName.contains('\\')) { "Zip entry '$entryName' contains a backslash." }
    require(!entryName.startsWith("/")) { "Zip entry '$entryName' is an absolute path." }
    require(!(entryName.length >= 2 && entryName[1] == ':' && entryName[0].isLetter())) {
        "Zip entry '$entryName' contains a Windows drive letter."
    }
    val outputFile = File(destinationDir, entryName)
    val destinationPrefix = destinationDir.canonicalPath + File.separator
    val outputCanonicalPath = outputFile.canonicalPath
    require(outputCanonicalPath.startsWith(destinationPrefix)) {
        "Zip entry '$entryName' escapes the destination directory."
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
                require(entryBytes <= limits.maxEntryBytes) {
                    "Zip entry '$entryName' exceeds per-file limit of ${limits.maxEntryBytes} bytes."
                }
                require(totalBytes <= limits.maxTotalBytes) {
                    "Zip archive exceeds total limit of ${limits.maxTotalBytes} bytes."
                }
                outStream.write(buffer, 0, read)
            }
        }
    } catch (e: Exception) {
        outputFile.delete()
        throw e
    }
    return totalBytes
}
