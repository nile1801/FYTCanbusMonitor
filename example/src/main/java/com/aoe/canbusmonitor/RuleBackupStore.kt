package com.aoe.canbusmonitor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Backup rule ra shared storage để file không nằm trong sandbox của app.
 *
 * Trên các ROM head-unit cho phép truy cập trực tiếp shared storage, app dùng đường dẫn
 * Download/FYTCanbusMonitor/rules.json. Trên Android scoped-storage chuẩn, app fallback
 * sang MediaStore.Downloads cho các lần đọc/ghi khi file vẫn thuộc quyền truy cập của app.
 */
object RuleBackupStore {
    const val FILE_NAME = "rules.json"
    const val RELATIVE_DIR = "Download/FYTCanbusMonitor/"
    const val DISPLAY_PATH = "$RELATIVE_DIR$FILE_NAME"

    fun read(context: Context): String? {
        readDirect()?.let { return it }
        return readMediaStore(context)
    }

    fun write(context: Context, text: String): Boolean {
        if (writeDirect(text)) return true
        return writeMediaStore(context, text)
    }

    private fun backupFile(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, "FYTCanbusMonitor"), FILE_NAME)
    }

    private fun readDirect(): String? {
        return try {
            val file = backupFile()
            if (!file.isFile) null else file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDirect(text: String): Boolean {
        return try {
            val file = backupFile()
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
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

    private fun findMediaStoreUri(context: Context): Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(FILE_NAME, RELATIVE_DIR)
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

    private fun readMediaStore(context: Context): String? {
        val uri = findMediaStoreUri(context) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeMediaStore(context: Context, text: String): Boolean {
        val resolver = context.contentResolver
        var uri = findMediaStoreUri(context)
        return try {
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
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
