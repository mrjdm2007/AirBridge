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

    fun receive(context: Context, socket: Socket): String {
        val input = DataInputStream(socket.getInputStream())
        val signature = ByteArray(4)
        input.readFully(signature)
        require(signature.contentEquals(magic)) { "Unrecognized AirBridge transfer." }
        val nameLength = input.readInt()
        val fileSize = input.readLong()
        require(nameLength in 1..1024 && fileSize >= 0) { "Invalid transfer header." }
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
                }
            }
            require(MessageDigest.isEqual(expectedHash, digest.digest())) { "File integrity check failed (SHA-256)." }
            saveToDownloads(context, temp, name)
            return name
        } finally {
            temp.delete()
        }
    }

    fun send(context: Context, uri: Uri, address: InetAddress, port: Int): String {
        val resolver = context.contentResolver
        val name = safeName(queryName(context, uri) ?: "AirBridge-file")
        val temp = File(context.cacheDir, "outgoing-${System.nanoTime()}.part")
        try {
            resolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "Cannot open the selected file." }
                FileOutputStream(temp).use { target -> source.copyTo(target) }
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
            Socket(address, port).use { socket ->
                socket.soTimeout = 120_000
                val output = DataOutputStream(socket.getOutputStream())
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                output.write(magic)
                output.writeInt(nameBytes.size)
                output.writeLong(temp.length())
                output.write(hash)
                output.write(nameBytes)
                temp.inputStream().use { it.copyTo(output, 64 * 1024) }
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
            .trim().take(180)
        return clean.ifBlank { "AirBridge-file" }
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
            source.copyTo(File(directory, name), overwrite = true)
        }
    }
}
