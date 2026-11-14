package com.airbridge.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest

object AirBridgeProtocol {
    private val magic = byteArrayOf(0x41, 0x42, 0x52, 0x31) // ABR1
    private const val MAX_TRANSFER_BYTES = 20L * 1024L * 1024L * 1024L

    fun receive(context: Context, socket: Socket, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        socket.soTimeout = 120_000
        val input = DataInputStream(socket.getInputStream())
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
                val buffer = ByteArray(64 * 1024)
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
            saveToDownloads(context, temp, name)
            return name
        } finally {
            temp.delete()
        }
    }

    fun send(context: Context, uri: Uri, address: InetAddress, port: Int, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val resolver = context.contentResolver
        val name = safeName(queryName(context, uri) ?: "AirBridge-file")
        val temp = File(context.cacheDir, "outgoing-${System.nanoTime()}.part")
        try {
            resolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "Cannot open the selected file." }
                FileOutputStream(temp).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_TRANSFER_BYTES) { "Files larger than 20 GiB are not supported." }
                        target.write(buffer, 0, count)
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = temp.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest()
            }
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(address, port), 15_000)
                socket.soTimeout = 120_000
                val output = DataOutputStream(socket.getOutputStream())
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                output.write(magic)
                output.writeInt(nameBytes.size)
                output.writeLong(temp.length())
                output.write(hash)
                output.write(nameBytes)
                temp.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        onProgress(sent, temp.length())
                    }
                }
                output.flush()
                require(socket.getInputStream().read() == 0x06) { "The receiving device did not confirm the file." }
            }
            return name
        } finally {
            temp.delete()
        }
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
