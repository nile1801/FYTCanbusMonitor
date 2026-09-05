package com.aoe.canbusmonitor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Backup config ra shared storage để file không nằm trong sandbox của app.
 * rules.json = config đang chạy; default_config.json = snapshot mặc định do user chủ động lưu.
 */
object RuleBackupStore {
    const val FILE_NAME = "rules.json"
    const val DEFAULT_CONFIG_FILE_NAME = "default_config.json"
    const val RELATIVE_DIR = "Download/FYTCanbusMonitor/"
    const val DISPLAY_PATH = "$RELATIVE_DIR$FILE_NAME"
    const val DEFAULT_CONFIG_DISPLAY_PATH = "$RELATIVE_DIR$DEFAULT_CONFIG_FILE_NAME"

    fun read(context: Context): String? = readNamed(context, FILE_NAME)
    fun write(context: Context, text: String): Boolean = writeNamed(context, FILE_NAME, text)

    fun readDefault(context: Context): String? = readNamed(context, DEFAULT_CONFIG_FILE_NAME)
    fun writeDefault(context: Context, text: String): Boolean = writeNamed(context, DEFAULT_CONFIG_FILE_NAME, text)

    private fun readNamed(context: Context, fileName: String): String? {
        readDirect(fileName)?.let { return it }
        return readMediaStore(context, fileName)
    }

    private fun writeNamed(context: Context, fileName: String, text: String): Boolean {
        if (writeDirect(fileName, text)) return true
        return writeMediaStore(context, fileName, text)
    }

    private fun backupFile(fileName: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, "FYTCanbusMonitor"), fileName)
    }

    private fun readDirect(fileName: String): String? {
        return try {
            val file = backupFile(fileName)
            if (!file.isFile) null else file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDirect(fileName: String, text: String): Boolean {
        return try {
            val file = backupFile(fileName)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$fileName.tmp")
            temp.writeText(text, Charsets.UTF_8)
            if (file.exists() && !file.delete()) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                temp.delete()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findMediaStoreUri(context: Context, fileName: String): Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, RELATIVE_DIR)
        return try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(0)
                Uri.withAppendedPath(collection, id.toString())
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readMediaStore(context: Context, fileName: String): String? {
        val uri = findMediaStoreUri(context, fileName) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeMediaStore(context: Context, fileName: String, text: String): Boolean {
        val resolver = context.contentResolver
        var uri = findMediaStoreUri(context, fileName)
        return try {
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return false
            }

            resolver.openOutputStream(uri!!, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false

            runCatching {
                resolver.update(
                    uri!!,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
