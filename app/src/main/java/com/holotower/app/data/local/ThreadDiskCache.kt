package com.holotower.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A bounded, compressed cache for raw thread JSON. Post objects stay in RAM only while in use. */
class ThreadDiskCache(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, CACHE_DIRECTORY)

    suspend fun read(board: String, threadNo: Long): String? = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            val now = System.currentTimeMillis()
            prune(now)
            val file = cacheFile(board, threadNo)
            if (!file.isFile) return@synchronized null

            try {
                FileInputStream(file).use { input ->
                    val savedAt = DataInputStream(input).readLong()
                    if (now - savedAt >= CACHE_TTL_MS || savedAt > now) {
                        file.delete()
                        null
                    } else {
                        val json = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        file.setLastModified(now)
                        json
                    }
                }
            } catch (_: Exception) {
                file.delete()
                null
            }
        }
    }

    suspend fun write(board: String, threadNo: Long, json: String) = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            val target = cacheFile(board, threadNo)
            if (json.length.toLong() > MAX_ENTRY_RAW_CHARS || !directory.exists() && !directory.mkdirs()) {
                target.delete()
                return@synchronized
            }

            val temporary = File(directory, "${target.name}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(longBytes(System.currentTimeMillis()))
                    GZIPOutputStream(output).writer(Charsets.UTF_8).use { writer -> writer.write(json) }
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    if (!temporary.renameTo(target)) {
                        target.delete()
                        if (!temporary.renameTo(target)) error("Unable to commit thread cache file")
                    }
                }
                target.setLastModified(System.currentTimeMillis())
                prune(System.currentTimeMillis())
            } catch (_: Exception) {
                temporary.delete()
            }
        }
    }

    suspend fun remove(board: String, threadNo: Long) = withContext(Dispatchers.IO) {
        synchronized(cacheLock) { cacheFile(board, threadNo).delete() }
    }

    private fun cacheFile(board: String, threadNo: Long): File {
        val key = "$board:$threadNo".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.json.gz")
    }

    private fun prune(nowMs: Long) {
        if (!directory.isDirectory) return
        directory.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        val entries = directory.listFiles { file -> file.isFile && file.name.endsWith(".json.gz") }
            ?.mapNotNull { file ->
                val savedAt = try {
                    DataInputStream(FileInputStream(file)).use { it.readLong() }
                } catch (_: Exception) {
                    file.delete()
                    return@mapNotNull null
                }
                if (nowMs - savedAt >= CACHE_TTL_MS || savedAt > nowMs) {
                    file.delete()
                    null
                } else {
                    file
                }
            }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

        var totalBytes = entries.sumOf { it.length() }
        for (file in entries) {
            if (totalBytes <= MAX_CACHE_BYTES) break
            totalBytes -= file.length()
            file.delete()
        }
    }

    private fun longBytes(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (56 - index * 8)).toByte()
    }

    private companion object {
        const val CACHE_DIRECTORY = "thread_json_cache"
        const val CACHE_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        const val MAX_CACHE_BYTES = 24L * 1024L * 1024L
        const val MAX_ENTRY_RAW_CHARS = 8L * 1024L * 1024L
        val cacheLock = Any()
    }
}
