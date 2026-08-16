package com.satanas1275.neobelieve.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import java.io.File

// Les flux googlevideo.com de YouTube répondent mal (coupures/throttle) sans
// User-Agent cohérent -> ExoPlayer partait en rebuffering en boucle, perçu comme
// un "play/pause qui n'arrête pas".
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) NeoBelieve/1.0"

/**
 * Coeur de la lecture. En passant par MediaSessionService (l'API média officielle
 * d'Android), on a "gratuitement" :
 *  - la notification de lecture avec les contrôles
 *  - l'intégration lockscreen / Android Auto / montres
 *  - un accès standard pour n'importe quelle app tierce qui veut lire "ce qui joue en ce
 *    moment" via un MediaController, sans avoir à coder une API maison.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var mediaCache: SimpleCache? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Cache disque LRU des morceaux déjà joués (300 Mo) : un titre souvent réécouté
        // reste en cache (pas re-téléchargé/re-extrait à chaque lecture), un titre jamais
        // réécouté se fait expulser automatiquement dès que la taille max est dépassée.
        // C'est la "logique intelligente" demandée, gratuite via Media3 plutôt que
        // réinventée à la main.
        val cache = SimpleCache(
            File(cacheDir, "media_cache"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(this),
        ).also { mediaCache = it }

        val upstreamFactory = OkHttpDataSource.Factory(OkHttpClient.Builder().build())
            .setUserAgent(USER_AGENT)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            // Explicite plutôt que de compter sur le défaut : garantit que les pochettes
            // (URL distante) sont bien chargées pour la notif, pas seulement le 1er titre.
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader(this)))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        mediaCache?.release()
        mediaCache = null
        super.onDestroy()
    }
}
