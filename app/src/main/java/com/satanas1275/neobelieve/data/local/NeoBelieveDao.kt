package com.satanas1275.neobelieve.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NeoBelieveDao {

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAtEpochMs DESC")
    fun observeDownloads(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks WHERE id = :trackId LIMIT 1")
    suspend fun getDownload(trackId: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(entity: DownloadedTrackEntity)

    @Delete
    suspend fun deleteDownload(entity: DownloadedTrackEntity)

    @Query("SELECT * FROM history ORDER BY playedAtEpochMs DESC LIMIT 100")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entity: HistoryEntity)
}
