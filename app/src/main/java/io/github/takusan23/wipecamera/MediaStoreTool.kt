package io.github.takusan23.wipecamera

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreTool {

    /**
     * 端末のギャラリーに登録する
     */
    suspend fun insertVideo(context: Context, videoFile: File) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val contentValues = contentValuesOf(
            MediaStore.MediaColumns.DISPLAY_NAME to videoFile.name,
            MediaStore.MediaColumns.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/WipeCamera"
        )
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            videoFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

}