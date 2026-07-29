package dev.ely.warp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What Warp keeps on disk.
 *
 * Three tables and one search index. Everything is local — conversations never
 * leave the phone, the same rule the keys follow.
 */

/**
 * A conversation.
 *
 * `titleIsManual` is the column that looks optional and is not. The auto-titler
 * runs after the first reply and would happily run again; this flag is what
 * stops it ever overwriting a name a person chose. A machine that quietly
 * renames the thing you just named is worse than one that never names anything.
 *
 * `deletedAt` makes delete undoable. A long-press menu puts Delete one slip of
 * the thumb away from Rename, and a conversation is unrecoverable work — so the
 * row leaves the list at once but the record survives until the undo window
 * closes. One nullable column buys back the only mistake in that menu that
 * cannot otherwise be taken back.
 */
@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            // Deleting a folder must not delete the conversations inside it.
            // They fall back to Recent, which is recoverable; cascading would
            // destroy work as a side effect of tidying up.
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("folderId"), Index("pinned"), Index("updatedAt")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(defaultValue = "0") val titleIsManual: Boolean = false,
    val folderId: String? = null,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Position within its folder, or within Recent. Lower is higher up. */
    @ColumnInfo(defaultValue = "0") val sortKey: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    /** Non-null while a delete is undoable. */
    val deletedAt: Long? = null,
)

/**
 * One message.
 *
 * Tool calls are stored as JSON rather than as their own table. They are only
 * ever read back with their message, never queried across, and a join for
 * something never queried is a table you maintain for nothing.
 *
 * `errorKind` and `errorDetail` were added after the first version shipped
 * without them, and the omission was not harmless. A failed reply has no text,
 * so a conversation reopened after two failures showed two blank gaps where two
 * error cards had been. Nothing was lost, but it was indistinguishable from the
 * app quietly eating the conversation — and an app you cannot trust to keep
 * things is one you stop putting things into.
 *
 * The kind is stored apart from the detail because the kind is a closed set the
 * app reasons about — whether a failure can be retried depends on it — while the
 * detail is opaque text from a provider. Flattening them into one string would
 * mean parsing that string back out to answer a question the schema can just
 * answer.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            // Here cascade IS right: a message without its conversation is
            // unreachable data.
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** Stored as the enum name, so a reordering of the enum cannot corrupt it. */
    val role: String,
    val text: String,
    val toolCallsJson: String? = null,
    /** The name of the [dev.ely.warp.ai.AiError] subclass, or null if it worked. */
    val errorKind: String? = null,
    /** Provider text for the kinds that carry any. */
    val errorDetail: String? = null,
    val createdAt: Long,
)

/** A folder. Flat — see the plan; folders inside folders in a drawer is a maze. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Manual order, set in the Manage folders sheet. Lower is higher up. */
    val sortKey: Long,
    val createdAt: Long,
)

/**
 * Full-text search over message bodies.
 *
 * FTS4, not FTS5: FTS5 is not guaranteed to be compiled into the SQLite that
 * ships with every Android build Warp supports, and a search feature that
 * crashes on someone's phone is worse than one built on the older engine.
 *
 * Room keeps this in step with `messages` through the content-table link, so
 * there is no second write path to forget about.
 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val text: String,
)
