package com.ivy.base.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                if (bytes != null) zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun freshDir(name: String): File = File(tmp.root, name)

    @Test
    fun validArchiveExtracts() {
        val bytes = archive("hello.txt" to "hello".toByteArray(), "nested/inner.txt" to "world".toByteArray())
        val dest = freshDir("fresh")

        unzip(ByteArrayInputStream(bytes), dest)

        assertEquals("hello", File(dest, "hello.txt").readText())
        assertEquals("world", File(dest, "nested/inner.txt").readText())
    }

    @Test
    fun fileInputStreamOverloadExtracts() {
        val zipFile = File(tmp.root, "a.zip")
        zipFile.writeBytes(archive("a.txt" to "data".toByteArray()))
        val dest = freshDir("fromFile")

        unzip(FileInputStream(zipFile), dest)

        assertEquals("data", File(dest, "a.txt").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun traversalEntryRejected() {
        unzip(ByteArrayInputStream(archive("../evil.txt" to "x".toByteArray())), freshDir("t1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nestedTraversalRejected() {
        unzip(ByteArrayInputStream(archive("sub/../../evil.txt" to "x".toByteArray())), freshDir("t2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun absolutePathRejected() {
        unzip(ByteArrayInputStream(archive("/tmp/evil.txt" to "x".toByteArray())), freshDir("t3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun backslashRejected() {
        unzip(ByteArrayInputStream(archive("evil\\file.txt" to "x".toByteArray())), freshDir("t4"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun windowsDriveRejected() {
        unzip(ByteArrayInputStream(archive("C:/evil.txt" to "x".toByteArray())), freshDir("t5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEntryNoOverwrite() {
        val bytes = archive("dup.txt" to "one".toByteArray(), "sub/../dup.txt" to "two".toByteArray())
        unzip(ByteArrayInputStream(bytes), freshDir("t6"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun perFileActualLimitEnforced() {
        val bytes = archive("big.bin" to ByteArray(16) { it.toByte() })
        unzip(ByteArrayInputStream(bytes), freshDir("t7"), UnzipLimits(maxEntryBytes = 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun totalActualLimitEnforced() {
        val bytes = archive("a.bin" to ByteArray(5), "b.bin" to ByteArray(5))
        unzip(
            ByteArrayInputStream(bytes),
            freshDir("t8"),
            UnzipLimits(maxEntryBytes = 1024, maxTotalBytes = 6)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun entryCountLimitEnforced() {
        val bytes = archive("a" to "1".toByteArray(), "b" to "2".toByteArray(), "c" to "3".toByteArray())
        unzip(ByteArrayInputStream(bytes), freshDir("t9"), UnzipLimits(maxEntries = 2))
    }

    @Test
    fun defaultLimitsMatchSpec() {
        assertEquals(32, DefaultMaxUnzipEntries)
        assertEquals(128L * 1024L * 1024L, DefaultMaxUnzipEntryBytes)
        assertEquals(256L * 1024L * 1024L, DefaultMaxUnzipTotalBytes)
    }

    @Test(expected = IllegalStateException::class)
    fun nonEmptyDestinationRejected() {
        val dest = tmp.newFolder("occupied")
        File(dest, "old.txt").writeText("old")
        unzip(ByteArrayInputStream(archive("n.txt" to "n".toByteArray())), dest)
    }

    @Test(expected = IllegalStateException::class)
    fun fileDestinationRejected() {
        val dest = tmp.newFile("afile")
        unzip(ByteArrayInputStream(archive("n.txt" to "n".toByteArray())), dest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedZipRejected() {
        unzip(ByteArrayInputStream("this is not a zip".toByteArray()), freshDir("t10"))
    }

    @Test
    fun inputStreamClosedAfterUnzip() {
        val bytes = archive("ok.txt" to "ok".toByteArray())
        val tracking = CloseTrackingInputStream(ByteArrayInputStream(bytes))

        unzip(tracking, freshDir("t11"))

        assertTrue(tracking.closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun directoryPayloadCannotBypassByteLimit() {
        unzip(
            ByteArrayInputStream(archive("directory/" to ByteArray(16))),
            freshDir("directoryData"),
            UnzipLimits(maxEntryBytes = 4)
        )
    }

    @Test
    fun rejectedDestinationClosesInputStream() {
        val tracking = CloseTrackingInputStream(ByteArrayInputStream(archive("ok" to byteArrayOf(1))))
        val destination = tmp.newFile("occupiedFile")
        try {
            unzip(tracking, destination)
            org.junit.Assert.fail("Expected occupied destination to fail")
        } catch (_: IllegalStateException) {
            assertTrue(tracking.closed)
        }
    }

    private class CloseTrackingInputStream(private val delegate: InputStream) : InputStream() {
        var closed = false
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
