package com.satanas1275.neobelieve.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Télécharge le flux audio d'un morceau dans le stockage privé de l'app
 * (pas besoin de permission stockage, et c'est nettoyé automatiquement si l'app est désinstallée).
 */
class DownloadTrackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Inconnu"
        val artist = inputData.getString(KEY_ARTIST) ?: "Inconnu"
        val duration = inputData.getInt(KEY_DURATION, 0)
        val thumbnail = inputData.getString(KEY_THUMBNAIL)

        val repository = MusicRepository.get(applicationContext)
        val track = Track(trackId, title, artist, duration, thumbnail)

        val streamUrl = repository.resolveStreamUrl(track) ?: return@withContext Result.failure()

        val downloadsDir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = File(downloadsDir, "$trackId.m4a")

        val client = OkHttpClient()
        val request = Request.Builder().url(streamUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext Result.retry()
            response.body?.byteStream()?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure()
        }

        repository.saveDownload(track, outFile.absolutePath)
        Result.success()
    }

    companion object {
        private const val KEY_TRACK_ID = "track_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_DURATION = "duration"
        private const val KEY_THUMBNAIL = "thumbnail"

        fun enqueue(context: Context, track: Track) {
            val data = workDataOf(
                KEY_TRACK_ID to track.id,
                KEY_TITLE to track.title,
                KEY_ARTIST to track.artist,
                KEY_DURATION to track.durationSeconds,
                KEY_THUMBNAIL to track.thumbnailUrl,
            )
            val request = OneTimeWorkRequestBuilder<DownloadTrackWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${track.id}",
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
