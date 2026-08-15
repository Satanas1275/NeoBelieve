package com.satanas1275.neobelieve.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val playedAtEpochMs: Long,
)
