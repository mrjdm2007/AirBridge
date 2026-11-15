package com.airbridge.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest

data class AirBridgeReceivedItem(val name: String, val textContent: String? = null)

object AirBridgeProtocol {
    private val magic = byteArrayOf(0x41, 0x42, 0x52, 0x31) // ABR1
    private const val MAX_TRANSFER_BYTES = 20L * 1024L * 1024L * 1024L
    private const val IO_BUFFER_BYTES = 4 * 1024 * 1024

    fun receive(context: Context, socket: Socket, onProgress: (Long, Long) -> Unit = { _, _ -> }): AirBridgeReceivedItem {
        socket.soTimeout = 120_000
        socket.tcpNoDelay = true
        socket.receiveBufferSize = IO_BUFFER_BYTES
        socket.sendBufferSize = IO_BUFFER_BYTES
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), IO_BUFFER_BYTES))
        val signature = ByteArray(4)
        input.readFully(signature)
        require(signature.contentEquals(magic)) { "Unrecognized AirBridge transfer." }
        val nameLength = input.readInt()
        val fileSize = input.readLong()
        require(nameLength in 1..1024 && fileSize in 0..MAX_TRANSFER_BYTES) { "Invalid transfer header or file exceeds the 20 GiB limit." }
        val available = context.cacheDir.usableSpace
        require(fileSize <= available) { "Not enough temporary storage to receive this file." }
        val expectedHash = ByteArray(32)
        input.readFully(expectedHash)
        val rawName = ByteArray(nameLength)
        input.readFully(rawName)
        val name = safeName(String(rawName, Charsets.UTF_8))
        val digest = MessageDigest.getInstance("SHA-256")
        val temp = File(context.cacheDir, "incoming-${System.nanoTime()}.part")
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                var remaining = fileSize
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw java.io.EOFException("Transfer ended before the file was complete.")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    remaining -= count
                    onProgress(fileSize - remaining, fileSize)
                }
            }
            require(MessageDigest.isEqual(expectedHash, digest.digest())) { "File integrity check failed (SHA-256)." }
            val textContent = if (name.startsWith("AirBridge-Text-", ignoreCase = true) && name.endsWith(".txt", ignoreCase = true)) temp.readText(Charsets.UTF_8) else null
            saveToDownloads(context, temp, name)
            return AirBridgeReceivedItem(name, textContent)
        } finally {
            temp.delete()
        }
    }

    fun send(context: Context, uri: Uri, address: InetAddress, port: Int, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val resolver = context.contentResolver
        val name = safeName(queryName(context, uri) ?: "AirBridge-file")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var totalBytes = 0L

        // Hash in a first read pass without writing a temporary copy to flash.
        resolver.openInputStream(uri).use { source ->
            requireNotNull(source) { "Cannot open the selected file." }
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                totalBytes += count
                require(totalBytes <= MAX_TRANSFER_BYTES) { "Files larger than 20 GiB are not supported." }
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest()
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.sendBufferSize = IO_BUFFER_BYTES
            socket.receiveBufferSize = IO_BUFFER_BYTES
            socket.connect(java.net.InetSocketAddress(address, port), 15_000)
            socket.soTimeout = 120_000
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), IO_BUFFER_BYTES))
            output.write(magic)
            output.writeInt(nameBytes.size)
            output.writeLong(totalBytes)
            output.write(hash)
            output.write(nameBytes)

            // Re-open the URI and stream directly to TCP, avoiding cache-file write/read overhead.
            resolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "The selected file could not be reopened for transfer." }
                var sent = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    sent += count
                    require(sent <= totalBytes) { "The selected file changed while being sent." }
                    output.write(buffer, 0, count)
                    onProgress(sent, totalBytes)
                }
                require(sent == totalBytes) { "The selected file changed while being sent." }
            }
            output.flush()
            require(socket.getInputStream().read() == 0x06) { "The receiving device did not confirm the file." }
        }
        return name
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        return uri.lastPathSegment
    }

    private fun safeName(value: String): String {
        val clean = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .replace(Regex("[<>:\"|?*]"), "_")
            .trim().trim('.').take(180)
        return clean.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "AirBridge-file"
    }

    private fun uniqueDestination(directory: File, name: String): File {
        val original = File(directory, name)
        if (!original.exists()) return original
        val base = original.nameWithoutExtension
        val extension = original.extension
        var index = 1
        while (true) {
            val candidateName = if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension"
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun saveToDownloads(context: Context, source: File, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirBridge")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the received file in Downloads.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: error("Could not write the received file.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AirBridge")
            check(directory.mkdirs() || directory.isDirectory)
            val destination = uniqueDestination(directory, name)
            source.copyTo(destination, overwrite = false)
        }
    }
}
