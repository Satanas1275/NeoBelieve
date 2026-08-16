package com.satanas1275.neobelieve.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.satanas1275.neobelieve.data.extractor.describeExtractionError
import com.satanas1275.neobelieve.data.model.Track
import com.satanas1275.neobelieve.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val TAG = "NeoBelieve.Download"

/**
 * Télécharge le flux audio d'un morceau dans le stockage privé de l'app
 * (pas besoin de permission stockage, et c'est nettoyé automatiquement si l'app est désinstallée).
 *
 * Tout est catché et loggé ici (au lieu de laisser WorkManager marquer un failure() muet) :
 * l'erreur exacte est renvoyée dans l'outputData sous KEY_ERROR pour que l'UI puisse
 * l'afficher directement, sans avoir besoin d'un logcat à chaque fois.
 */
class DownloadTrackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID)
            ?: return@withContext failWith("track_id manquant")
        val title = inputData.getString(KEY_TITLE) ?: "Inconnu"
        val artist = inputData.getString(KEY_ARTIST) ?: "Inconnu"
        val duration = inputData.getInt(KEY_DURATION, 0)
        val thumbnail = inputData.getString(KEY_THUMBNAIL)

        val repository = MusicRepository.get(applicationContext)
        val track = Track(trackId, title, artist, duration, thumbnail)

        val streamUrl = try {
            repository.resolveStreamUrl(track)
        } catch (e: Exception) {
            Log.e(TAG, "resolveStreamUrl a levé une exception pour $trackId", e)
            return@withContext failWith("extraction du flux : ${e.describeExtractionError()}")
        } ?: return@withContext failWith("aucun flux audio trouvé pour ce morceau")

        val downloadsDir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = File(downloadsDir, "$trackId.m4a")

        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(streamUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code} en téléchargeant $streamUrl")
                    return@withContext failWith("HTTP ${response.code} lors du téléchargement")
                }
                val body = response.body ?: return@withContext failWith("réponse vide du serveur")
                body.byteStream().use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Téléchargement du flux échoué pour $trackId", e)
            return@withContext failWith("téléchargement : ${e.describeExtractionError()}")
        }

        try {
            repository.saveDownload(track, outFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Sauvegarde en base échouée pour $trackId", e)
            return@withContext failWith("sauvegarde locale : ${e.describeExtractionError()}")
        }

        Result.success()
    }

    private fun failWith(reason: String): Result =
        Result.failure(workDataOf(KEY_ERROR to reason))

    companion object {
        private const val KEY_TRACK_ID = "track_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_DURATION = "duration"
        private const val KEY_THUMBNAIL = "thumbnail"
        const val KEY_ERROR = "error_reason"

        fun enqueue(context: Context, track: Track): String {
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
            val workName = "download_${track.id}"
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
            return workName
        }
    }
}
