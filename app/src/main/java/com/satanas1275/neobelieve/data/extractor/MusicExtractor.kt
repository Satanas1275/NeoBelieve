package com.satanas1275.neobelieve.data.extractor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import com.satanas1275.neobelieve.data.model.Track

/**
 * Point d'entrée unique pour choper de la musique gratuitement, sans clé API,
 * en s'appuyant sur NewPipeExtractor (le même moteur que ReVanced/InnerTune/ViMusic).
 *
 * IMPORTANT : à tester sur device, les content filters YouTube changent parfois
 * de nom côté extracteur selon la version. "music_songs" est le filtre attendu
 * en v0.24.x pour restreindre aux morceaux (vs vidéos classiques).
 */
object MusicExtractor {

    private var initialized = false

    private fun ensureInit() {
        if (!initialized) {
            NewPipe.init(OkHttpDownloader.instance)
            initialized = true
        }
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        ensureInit()
        val service = ServiceList.YouTube
        val searchExtractor = service.getSearchExtractor(query, listOf("music_songs"), "")
        searchExtractor.fetchPage()

        searchExtractor.initialPage.items
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .map { it.toTrack() }
    }

    /** Flux audio direct pour jouer un morceau (choisit le meilleur bitrate audio-only dispo). */
    suspend fun getStreamUrl(trackId: String): String? = withContext(Dispatchers.IO) {
        ensureInit()
        val url = "https://www.youtube.com/watch?v=$trackId"
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        info.audioStreams
            .maxByOrNull { it.averageBitrate }
            ?.content
    }

    /**
     * "Radio auto" façon YouTube Music : quand on lance juste un titre (pas une playlist),
     * on peuple la suite avec les morceaux liés retournés par l'extracteur.
     */
    suspend fun getRelated(trackId: String): List<Track> = withContext(Dispatchers.IO) {
        ensureInit()
        val url = "https://www.youtube.com/watch?v=$trackId"
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        info.relatedItems
            ?.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            ?.map { it.toTrack() }
            ?: emptyList()
    }

    private fun org.schabi.newpipe.extractor.stream.StreamInfoItem.toTrack(): Track {
        val id = url.substringAfter("watch?v=").substringBefore("&")
        return Track(
            id = id,
            title = name,
            artist = uploaderName ?: "Inconnu",
            durationSeconds = duration.toInt(),
            // Les vignettes des résultats de recherche NewPipeExtractor sont basse résolution
            // (~120-360px) -> pixelisées en plein écran. L'URL statique YouTube pointe vers
            // une image nette (480x360) et existe pour quasiment toutes les vidéos publiques.
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        )
    }
}
