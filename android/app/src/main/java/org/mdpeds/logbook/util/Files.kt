package org.mdpeds.logbook.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Writes generated/downloaded files to the private cache and opens or shares them via the FileProvider. */
object Files {
    const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

    private fun dir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "file" }

    fun write(context: Context, name: String, bytes: ByteArray): File {
        val f = File(dir(context), safeName(name))
        f.writeBytes(bytes)
        return f
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Opens the file in a viewer app; returns false if none is installed. */
    fun view(context: Context, file: File, mime: String = mimeOf(file.name)): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun share(context: Context, file: File, mime: String = mimeOf(file.name), title: String = "Share") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a file to a user-chosen location (Storage Access Framework). */
    fun copyTo(context: Context, file: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    data class Picked(val name: String, val mime: String, val bytes: ByteArray)

    /** Reads a document chosen with OpenDocument, enforcing the upload size limit. */
    fun read(context: Context, uri: Uri): Picked {
        val resolver = context.contentResolver
        var name = "attachment"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        require(size <= MAX_UPLOAD_BYTES) { "File is larger than 10 MB" }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read the file")
        require(bytes.size <= MAX_UPLOAD_BYTES) { "File is larger than 10 MB" }
        return Picked(name, resolver.getType(uri) ?: mimeOf(name), bytes)
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
