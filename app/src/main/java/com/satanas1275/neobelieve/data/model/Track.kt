package com.satanas1275.neobelieve.data.model

/**
 * Représentation unifiée d'un morceau, qu'il vienne d'une recherche en ligne,
 * d'une recommandation auto, ou d'un fichier déjà téléchargé en local.
 */
data class Track(
    val id: String,          // id vidéo YouTube Music (sert de clé unique partout)
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
)

/** D'où vient la queue actuelle : une vraie playlist, ou juste "radio auto" après un titre lancé seul. */
enum class QueueSource {
    PLAYLIST,
    AUTO_RADIO,
}
