package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipImageStreamTest {
    private fun image(bytes: ByteArray, archive: ByteArray): ImageRef = Images.COLOROS17_PLK110.copy(
        gzName = "sample.zip",
        gzBytes = archive.size.toLong(),
        gzSha256 = sha(archive),
        rawBytes = bytes.size.toLong(),
        rawSha256 = sha(bytes),
    )

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rawImage(): ByteArray = ByteArray(1100).also {
        it[1080] = 0x53
        it[1081] = 0xef.toByte()
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun copiesAndChecksPinnedSystemImage() {
        val raw = rawImage()
        val zip = archive("system.img" to raw)
        val output = ByteArrayOutputStream()
        val result = ZipImageStream.copy(ByteArrayInputStream(zip), output, image(raw, zip))

        assertEquals(raw.toList(), output.toByteArray().toList())
        assertEquals(zip.size.toLong(), result.archiveBytes)
        assertEquals(raw.size.toLong(), result.imageBytes)
        assertEquals(sha(zip), result.archiveSha256)
        assertEquals(sha(raw), result.imageSha256)
    }

    @Test
    fun hashesTheWholeArchiveIncludingTrailingBytes() {
        val raw = rawImage()
        val zip = archive("system.img" to raw) + ByteArray(2 shl 20) { 0x5a }
        val input = ByteArrayInputStream(zip)
        val result = ZipImageStream.copy(input, ByteArrayOutputStream(), image(raw, zip))

        assertEquals(zip.size.toLong(), result.archiveBytes)
        assertEquals(0, input.available())
        assertEquals(sha(zip), result.archiveSha256)
    }

    @Test
    fun rejectsWrongEntryNamesAndExtraEntries() {
        val raw = rawImage()
        val renamed = archive("vendor.img" to raw)
        assertFails("system.img") { ZipImageStream.copy(ByteArrayInputStream(renamed), ByteArrayOutputStream(), image(raw, renamed)) }

        val extra = archive("system.img" to raw, "vendor.img" to byteArrayOf(1))
        assertFails("exactly one entry") { ZipImageStream.copy(ByteArrayInputStream(extra), ByteArrayOutputStream(), image(raw, extra)) }
    }

    @Test
    fun rejectsBadExt4AndPinnedHashes() {
        val badHeader = ByteArray(1100)
        val badHeaderZip = archive("system.img" to badHeader)
        assertFails("not an ext4") { ZipImageStream.copy(ByteArrayInputStream(badHeaderZip), ByteArrayOutputStream(), image(badHeader, badHeaderZip)) }

        val raw = rawImage()
        val zip = archive("system.img" to raw)
        val wrongPin = image(raw, zip).copy(rawSha256 = "0".repeat(64))
        assertFails("system.img SHA-256") { ZipImageStream.copy(ByteArrayInputStream(zip), ByteArrayOutputStream(), wrongPin) }
    }

    private fun assertFails(message: String, run: () -> Unit) {
        val error = runCatching(run).exceptionOrNull()
        assertTrue("expected IOException containing '$message', got $error", error is IOException && error.message.orEmpty().contains(message))
    }
}
