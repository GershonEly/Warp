package dev.ely.warp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Warp's local store.
 *
 * One database, opened once. Nothing here syncs anywhere — the same rule the
 * key vault follows, for the same reason.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FolderEntity::class,
        MessageFts::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WarpDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun folders(): FolderDao

    companion object {
        @Volatile
        private var instance: WarpDatabase? = null

        /**
         * The single instance.
         *
         * Double-checked locking rather than a lazy property because the first
         * call can come from either the UI or a background write, and two open
         * handles to the same SQLite file is a corruption story waiting to
         * happen.
         */
        fun get(context: Context): WarpDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WarpDatabase::class.java,
                    "warp.db",
                )
                    // Deliberately no fallbackToDestructiveMigration. Losing
                    // someone's conversations because a column moved is not an
                    // acceptable failure mode; a missing migration should break
                    // the build's tests, not the user's history.
                    .build()
                    .also { instance = it }
            }
    }
}
