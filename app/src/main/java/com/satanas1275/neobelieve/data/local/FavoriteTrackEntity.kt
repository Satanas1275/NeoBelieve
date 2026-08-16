package com.satanas1275.neobelieve.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteTrackEntity(
    @PrimaryKey val id: String, // même id que Track.id
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val favoritedAtEpochMs: Long,
)
