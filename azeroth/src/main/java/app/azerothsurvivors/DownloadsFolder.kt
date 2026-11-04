package app.azerothsurvivors

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Files the app hands to the player go to Download/Azeroth Survivors/. */
object DownloadsFolder {
    const val NAME = "Azeroth Survivors"

    /** Writes [bytes] as [name] and returns the name it was saved under. */
    fun write(context: Context, name: String, bytes: ByteArray, mime: String = "application/octet-stream"): String {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "AzerothSurvivors.txt" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$NAME")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads folder")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("can't write")
        return safe
    }
}
