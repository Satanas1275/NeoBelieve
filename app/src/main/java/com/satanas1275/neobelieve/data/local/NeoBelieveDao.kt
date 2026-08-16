package com.satanas1275.neobelieve.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ArtistStat(val artist: String, val plays: Int)
data class TrackStat(val trackId: String, val title: String, val artist: String, val thumbnailUrl: String?, val plays: Int)

@Dao
interface NeoBelieveDao {

    // --- Téléchargements ---
    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAtEpochMs DESC")
    fun observeDownloads(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks WHERE id = :trackId LIMIT 1")
    suspend fun getDownload(trackId: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(entity: DownloadedTrackEntity)

    @Delete
    suspend fun deleteDownload(entity: DownloadedTrackEntity)

    // --- Historique ---
    @Query("SELECT * FROM history ORDER BY playedAtEpochMs DESC LIMIT 200")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entity: HistoryEntity)

    @Query("""
        SELECT artist, COUNT(*) as plays FROM history
        GROUP BY artist ORDER BY plays DESC LIMIT :limit
    """)
    suspend fun topArtists(limit: Int = 5): List<ArtistStat>

    @Query("""
        SELECT trackId, title, artist, thumbnailUrl, COUNT(*) as plays FROM history
        GROUP BY trackId ORDER BY plays DESC LIMIT :limit
    """)
    suspend fun topTracks(limit: Int = 5): List<TrackStat>

    @Query("SELECT COUNT(*) FROM history")
    suspend fun totalPlays(): Int

    // --- Favoris ---
    @Query("SELECT * FROM favorites ORDER BY favoritedAtEpochMs DESC")
    fun observeFavorites(): Flow<List<FavoriteTrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :trackId)")
    fun observeIsFavorite(trackId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(entity: FavoriteTrackEntity)

    @Query("DELETE FROM favorites WHERE id = :trackId")
    suspend fun deleteFavorite(trackId: String)

    // --- Playlists ---
    @Query("SELECT * FROM playlists ORDER BY createdAtEpochMs DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insertPlaylist(entity: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observePlaylistTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun playlistTrackCount(playlistId: Long): Int

    @Query("SELECT MIN(position) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun minPosition(playlistId: Long): Int?

    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(entity: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removePlaylistTrack(playlistId: Long, trackId: String)
}
