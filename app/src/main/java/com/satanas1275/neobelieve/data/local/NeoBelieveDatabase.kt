package com.satanas1275.neobelieve.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadedTrackEntity::class, HistoryEntity::class],
    version = 1,
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
                ).build().also { instance = it }
            }
    }
}
