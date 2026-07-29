package dev.ely.warp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reading and writing conversations.
 *
 * Every list query filters `deletedAt IS NULL`. Soft-deleted rows are invisible
 * everywhere except the undo path and the sweeper — if a query here ever needs
 * to see them, that is a bug in the caller, not a missing parameter.
 */
@Dao
interface ConversationDao {

    @Query(
        """
        SELECT * FROM conversations
        WHERE deletedAt IS NULL
        ORDER BY pinned DESC, sortKey ASC, updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id AND deletedAt IS NULL")
    suspend fun byId(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, titleIsManual = 1, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    /**
     * Used only by the auto-titler.
     *
     * The `titleIsManual = 0` in the WHERE clause is the guard, and it lives
     * here rather than in Kotlin on purpose: a check done in the query cannot
     * lose a race with someone renaming the conversation while the model is
     * still thinking of a name.
     */
    @Query(
        """
        UPDATE conversations SET title = :title
        WHERE id = :id AND titleIsManual = 0
        """
    )
    suspend fun suggestTitle(id: String, title: String)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE conversations SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    // ── delete, in two stages ────────────────────────────────────────────

    /** Stage one: gone from every list, still on disk. */
    @Query("UPDATE conversations SET deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** The undo. */
    @Query("UPDATE conversations SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /**
     * Stage two: actually gone.
     *
     * Called on startup rather than on a timer. A timer only fires if the app
     * stays alive, and the one thing you can rely on a phone doing is killing
     * your process — so anything still soft-deleted from a previous session is
     * swept the next time Warp opens.
     */
    @Query("DELETE FROM conversations WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long)
}

/**
 * Reading and writing messages.
 *
 * Every ordering here breaks ties on `rowid`, and that is not belt-and-braces.
 * A question and its reply are created in the same instant — measured on the
 * device, they land on the identical millisecond — and `ORDER BY createdAt`
 * alone leaves SQLite free to return them either way round. That shows up as a
 * transcript where the answer sits above the question, but only sometimes, and
 * only after a reload. `rowid` is insertion order, and the question is always
 * written before its reply, so it settles the tie the way a reader expects.
 */
@Dao
interface MessageDao {

    @Query(
        """
        SELECT * FROM messages WHERE conversationId = :conversationId
        ORDER BY createdAt ASC, rowid ASC
        """
    )
    fun observeFor(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages WHERE conversationId = :conversationId
        ORDER BY createdAt ASC, rowid ASC
        """
    )
    suspend fun forConversation(conversationId: String): List<MessageEntity>

    /**
     * The preview line under a title.
     *
     * A list of bare titles gives you nothing to recognise a conversation by
     * when two of them are named similarly.
     *
     * The `rowid` tiebreak matters here for the same reason as above, and it is
     * more visible: without it the preview under a conversation can show the
     * question instead of the answer, because the two share a timestamp and the
     * "last message" is then whichever one SQLite felt like returning.
     *
     * Empty messages are skipped, and that is not tidiness. A reply that failed
     * has no text, so the newest message in a conversation that ended badly is a
     * blank one — and taking it literally gave every such conversation a blank
     * preview line for ever. The last message that actually said something is
     * what tells you which conversation this is, which is the only reason the
     * line exists.
     */
    @Query(
        """
        SELECT conversationId, text FROM messages
        WHERE id IN (
            SELECT id FROM messages m
            WHERE m.conversationId = messages.conversationId AND m.text != ''
            ORDER BY m.createdAt DESC, m.rowid DESC LIMIT 1
        )
        """
    )
    fun observePreviews(): Flow<List<Preview>>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: String)

    /**
     * Search.
     *
     * Joined against the FTS shadow table rather than using LIKE, so it stays
     * fast once there are thousands of messages and matches on word boundaries
     * instead of on substrings.
     */
    @Transaction
    @Query(
        """
        SELECT DISTINCT c.* FROM conversations c
        JOIN messages m ON m.conversationId = c.id
        JOIN messages_fts fts ON fts.rowid = m.rowid
        WHERE messages_fts MATCH :query AND c.deletedAt IS NULL
        ORDER BY c.pinned DESC, c.updatedAt DESC
        """
    )
    suspend fun search(query: String): List<ConversationEntity>

    data class Preview(val conversationId: String, val text: String)
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY sortKey ASC, createdAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Upsert
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)
}
