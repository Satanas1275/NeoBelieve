package com.satanas1275.neobelieve.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadedTrackEntity::class,
        HistoryEntity::class,
        FavoriteTrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class NeoBelieveDatabase : RoomDatabase() {
    abstract fun dao(): NeoBelieveDao

    companion object {
        @Volatile private var instance: NeoBelieveDatabase? = null

        fun get(context: Context): NeoBelieveDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeoBelieveDatabase::class.java,
                    "neobelieve.db",
                )
                    // v1 (avant playlists/favoris) : pas encore en prod, on accepte de
                    // perdre les données locales plutôt que d'écrire des migrations pour rien.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
