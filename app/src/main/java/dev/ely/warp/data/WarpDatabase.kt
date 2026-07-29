package dev.ely.warp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
         * Somewhere to keep a failure.
         *
         * Written rather than reached for `fallbackToDestructiveMigration`, even
         * though Warp is unreleased and the only history in existence is on one
         * phone. Two reasons, and the second is the real one:
         *
         * Adding two nullable columns is four lines. Wiping is one. The saving
         * is not worth anything.
         *
         * And this is the first migration this app has ever needed. Whatever
         * happens here is the habit — the next one arrives when there are users,
         * and a codebase where the previous migration was "delete it all" is one
         * where that looks like the normal answer.
         *
         * Both columns are nullable with no default, which is exactly what the
         * entity declares; a mismatch here fails Room's identity check at open
         * time rather than silently.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN errorKind TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN errorDetail TEXT")
            }
        }

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
                    .addMigrations(MIGRATION_1_2)
                    // Deliberately no fallbackToDestructiveMigration. Losing
                    // someone's conversations because a column moved is not an
                    // acceptable failure mode; a missing migration should break
                    // the build's tests, not the user's history.
                    .build()
                    .also { instance = it }
            }
    }
}
