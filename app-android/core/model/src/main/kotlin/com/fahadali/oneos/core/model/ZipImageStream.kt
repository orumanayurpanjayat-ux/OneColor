package com.fahadali.oneos.core.model

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Streams the single pinned `system.img` from a verified ZIP without extracting it to storage. */
object ZipImageStream {
    data class Result(val archiveBytes: Long, val archiveSha256: String, val imageBytes: Long, val imageSha256: String)

    /**
     * Copies only a single `system.img` member, and validates its ext4 header,
     * uncompressed size/hash, archive size/hash and ZIP CRC. Extra or renamed
     * entries are rejected. Cancellation is checked between output chunks.
     */
    @Throws(IOException::class)
    fun copy(
        input: InputStream,
        output: OutputStream,
        image: ImageRef,
        cancelled: () -> Boolean = { false },
        progress: (archiveBytes: Long, imageBytes: Long) -> Unit = { _, _ -> },
    ): Result {
        require(image.inputFormat == ImageInputFormat.ZIP) { "${image.id} is not a ZIP image" }
        val archiveMd = MessageDigest.getInstance("SHA-256")
        val imageMd = MessageDigest.getInstance("SHA-256")
        var archiveBytes = 0L
        val counted = object : InputStream() {
            private val source = BufferedInputStream(input, 1 shl 20)

            override fun read(): Int {
                val value = source.read()
                if (value >= 0) {
                    archiveMd.update(value.toByte())
                    archiveBytes++
                }
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = source.read(buffer, offset, length)
                if (n > 0) {
                    archiveMd.update(buffer, offset, n)
                    archiveBytes += n
                }
                return n
            }

            override fun close() = source.close()
        }

        var imageBytes = 0L
        var lastProgress = 0L
        val header = ByteArray(Images.EXT4_MAGIC_OFFSET.toInt() + 2)
        var headerBytes = 0
        val archiveSha: String
        val imageSha: String
        ZipInputStream(counted, StandardCharsets.UTF_8).use { zip ->
            val entry = zip.nextEntry ?: throw IOException("the ZIP archive is empty")
            if (entry.isDirectory || entry.name != "system.img") throw IOException("the ZIP must contain a system.img entry")
            if (entry.size >= 0 && entry.size != image.rawBytes) throw IOException("system.img size does not match the pinned image")

            val buffer = ByteArray(1 shl 18)
            while (true) {
                if (cancelled()) throw IOException("stopped")
                val n = zip.read(buffer)
                if (n < 0) break
                if (imageBytes + n > image.rawBytes) throw IOException("system.img is larger than ${image.rawBytes} bytes")
                if (headerBytes < header.size) {
                    val take = minOf(n, header.size - headerBytes)
                    System.arraycopy(buffer, 0, header, headerBytes, take)
                    headerBytes += take
                    if (headerBytes == header.size) {
                        val magic = (header[Images.EXT4_MAGIC_OFFSET.toInt()].toInt() and 0xff) or
                            ((header[Images.EXT4_MAGIC_OFFSET.toInt() + 1].toInt() and 0xff) shl 8)
                        if (magic != Images.EXT4_MAGIC) throw IOException("system.img is not an ext4 image")
                    }
                }
                imageMd.update(buffer, 0, n)
                output.write(buffer, 0, n)
                imageBytes += n
                if (imageBytes - lastProgress >= 64L * 1024 * 1024) {
                    lastProgress = imageBytes
                    progress(archiveBytes, imageBytes)
                }
            }
            // closeEntry performs the CRC check even when the stream reached its data boundary.
            zip.closeEntry()
            if (zip.nextEntry != null) throw IOException("the ZIP must contain exactly one entry")
            // ZipInputStream stops at the central directory and does not expose trailing
            // bytes to its caller. Drain the counted source itself so the archive pin covers
            // the central directory, end record, and any bytes after it as well.
            val drain = ByteArray(1 shl 16)
            while (counted.read(drain) >= 0) {
                if (cancelled()) throw IOException("stopped")
            }
            output.flush()
            archiveSha = Hashes.hex(archiveMd.digest())
            imageSha = Hashes.hex(imageMd.digest())
        }
        progress(archiveBytes, imageBytes)
        if (imageBytes != image.rawBytes) throw IOException("system.img has $imageBytes bytes, not ${image.rawBytes}")
        if (imageSha != image.rawSha256) throw IOException("system.img SHA-256 differs from the pinned image")
        if (archiveBytes != image.gzBytes) throw IOException("the ZIP has $archiveBytes bytes, not ${image.gzBytes}")
        if (archiveSha != image.gzSha256) throw IOException("the ZIP SHA-256 differs from the pinned archive")
        return Result(archiveBytes, archiveSha, imageBytes, imageSha)
    }
}
