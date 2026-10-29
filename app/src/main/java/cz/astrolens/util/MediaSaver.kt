package cz.astrolens.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Saves photos into Pictures/AstroLens through MediaStore. */
object MediaSaver {
    private const val FOLDER = "AstroLens"

    /** Returns a human readable location of the saved file or null on failure. */
    fun save(context: Context, displayName: String, mime: String, writer: (OutputStream) -> Unit): String? {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val os = resolver.openOutputStream(uri)
                if (os != null) {
                    os.use { writer(it) }
                    val done = ContentValues()
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, done, null, null)
                    return "Obrázky/" + FOLDER + "/" + displayName
                }
            }
        } catch (e: Exception) {
            // fall through to the app-private fallback below
        }
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val f = File(dir, displayName)
            FileOutputStream(f).use { writer(it) }
            f.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
