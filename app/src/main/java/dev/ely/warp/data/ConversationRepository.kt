package dev.ely.warp.data

import android.content.Context
import dev.ely.warp.ai.AiError
import dev.ely.warp.ai.ChatEngine
import dev.ely.warp.ai.ChatMessage
import dev.ely.warp.ai.Role
import dev.ely.warp.ai.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The only thing that talks to the database.
 *
 * Everything above this file works in `ChatMessage` and [Conversation]; the
 * entities never leave. That boundary is the point — the chat should not know
 * that tool calls happen to be stored as JSON, and the store should not know
 * that a message can be mid-stream.
 */
class ConversationRepository(context: Context) : ChatEngine.ConversationStore {

    private val db = WarpDatabase.get(context)
    private val conversations = db.conversations()
    private val messages = db.messages()
    private val folders = db.folders()

    // ── reading ──────────────────────────────────────────────────────────

    /**
     * Everything the drawer needs, in one stream.
     *
     * Combined here rather than collected as three separate flows in the UI:
     * three flows means three recompositions per change and a window where the
     * list has been updated but its previews have not, which shows up as text
     * flickering between conversations.
     */
    fun observeDrawer(): Flow<DrawerState> =
        combine(
            conversations.observeAll(),
            folders.observeAll(),
            messages.observePreviews(),
        ) { conversationRows, folderRows, previewRows ->
            val previews = previewRows.associate { it.conversationId to it.text }
            DrawerState(
                conversations = conversationRows.map { it.toModel(previews[it.id]) },
                folders = folderRows.map { Folder(it.id, it.name, it.sortKey) },
            )
        }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messages.observeFor(conversationId).map { rows -> rows.map { it.toModel() } }

    /**
     * Read a transcript once, for opening it.
     *
     * Deliberately not a flow. While a reply is streaming, the engine holds the
     * live message list and writes it out; a flow feeding back in would have the
     * screen rendering the database's idea of the text a beat behind the
     * engine's, which reads as the answer stuttering as it arrives. The store is
     * where a conversation is kept, not where it happens.
     */
    suspend fun loadMessages(conversationId: String): List<ChatMessage> =
        messages.forConversation(conversationId).map { it.toModel() }

    /**
     * Conversations whose **name** contains what was typed.
     *
     * Names, not message bodies, and that was decided by using it: a chat called
     * "yo yo yo" could not be found by typing its own name, while a different
     * chat that happened to contain those words *inside* it came back instead.
     * Searching everything makes the thing you are looking at unfindable by the
     * one label you gave it.
     *
     * The FTS index over message text still exists and still works — see
     * [searchMessages]. It is the right tool for "somewhere I discussed
     * Gradle", which is a different question from "where is that chat called
     * yo yo yo", and it belongs behind its own control rather than silently
     * competing with this one.
     *
     * `%` and `_` are escaped rather than stripped: they are LIKE wildcards, so
     * an unescaped "50%" would match every conversation there is.
     */
    suspend fun searchByName(
        query: String,
        order: ConversationOrder = ConversationOrder.RECENT,
    ): List<Conversation> {
        val name = query.trim()
            .replace("""\""", """\\""")
            .replace("%", """\%""")
            .replace("_", """\_""")
        if (name.isEmpty()) return emptyList()

        val rows = when (order) {
            ConversationOrder.RECENT -> conversations.searchByNameRecent(name)
            ConversationOrder.ALPHABETICAL -> conversations.searchByNameAlphabetical(name)
        }
        return rows.map { it.toModel(null) }
    }

    /**
     * Conversations containing a word *inside* a message.
     *
     * Not wired to the drawer's search box — see [searchByName] for why. Kept
     * because it works, it is tested, and "find the chat where I mentioned
     * kotlinc" is a real question that will want answering.
     *
     * Each word becomes its own prefix term. Quoting the whole query as a phrase
     * looked equivalent and is not: quoting makes it an exact phrase match and
     * the trailing star stops being a prefix operator, so whole words matched
     * and partial ones silently never did.
     */
    suspend fun searchMessages(query: String): List<Conversation> {
        val terms = query.split(FTS_SPLIT).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        val match = terms.joinToString(" ") { "$it*" }
        return runCatching { messages.search(match) }
            .getOrDefault(emptyList())
            .map { it.toModel(null) }
    }

    // ── what the chat engine writes through ──────────────────────────────
    //
    // The engine is handed no clock, so the two methods it calls read one here.
    // Everything else takes `now` explicitly, which is what makes the store
    // testable without waiting for real time to pass.

    override suspend fun create(): String = createConversation(System.currentTimeMillis())

    override suspend fun save(conversationId: String, message: ChatMessage) =
        saveMessage(conversationId, message, System.currentTimeMillis())

    // ── writing ──────────────────────────────────────────────────────────

    suspend fun createConversation(now: Long): String {
        val id = UUID.randomUUID().toString()
        conversations.upsert(
            ConversationEntity(
                id = id,
                title = UNTITLED,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id
    }

    /**
     * Write a message, and mark its conversation as freshly used.
     *
     * Called on every streamed chunk, so it is an upsert of one row rather than
     * a rewrite of the conversation — SQLite is fast, but not "rewrite the
     * transcript sixty times a second" fast.
     */
    suspend fun saveMessage(conversationId: String, message: ChatMessage, now: Long) {
        messages.upsert(
            MessageEntity(
                id = message.id,
                conversationId = conversationId,
                role = message.role.name,
                text = message.text,
                toolCallsJson = message.toolCalls.toJsonOrNull(),
                errorKind = message.error?.storageKind(),
                errorDetail = message.error?.storageDetail(),
                createdAt = message.createdAt,
            )
        )
        conversations.touch(conversationId, now)
    }

    suspend fun rename(id: String, title: String, now: Long) =
        conversations.rename(id, title.trim().ifEmpty { UNTITLED }, now)

    /** Only the auto-titler calls this; it cannot overwrite a manual name. */
    suspend fun suggestTitle(id: String, title: String) {
        val clean = title.trim().trim('"', '\'', '.').take(60)
        if (clean.isNotEmpty()) conversations.suggestTitle(id, clean)
    }

    /**
     * The stored title, or null when the row is gone or soft-deleted.
     *
     * Exists so a caller can read back what a write actually did rather than
     * trust that it returned. Null is the honest answer for a deleted row: the
     * list query hides it, so "not listed" is what the rest of the app sees.
     */
    suspend fun titleOf(id: String): String? = conversations.byId(id)?.title

    suspend fun isPinned(id: String): Boolean? = conversations.byId(id)?.pinned

    suspend fun setPinned(id: String, pinned: Boolean, now: Long) =
        conversations.setPinned(id, pinned, now)

    suspend fun moveToFolder(id: String, folderId: String?, now: Long) =
        conversations.moveToFolder(id, folderId, now)

    suspend fun softDelete(id: String, now: Long) = conversations.softDelete(id, now)

    suspend fun undoDelete(id: String) = conversations.restore(id)

    /**
     * Clear out anything whose undo window has closed.
     *
     * Run at startup. See the DAO for why this is not a timer.
     */
    suspend fun purgeOldDeletes(now: Long) =
        conversations.purgeDeletedBefore(now - UNDO_WINDOW_MS)

    // ── folders ──────────────────────────────────────────────────────────

    suspend fun createFolder(name: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        folders.upsert(FolderEntity(id, name.trim(), sortKey = now, createdAt = now))
        return id
    }

    suspend fun renameFolder(id: String, name: String) = folders.rename(id, name.trim())

    suspend fun deleteFolder(folder: Folder) =
        folders.delete(FolderEntity(folder.id, folder.name, folder.sortKey, 0))

    /** Persist a new order after a drag in the Manage folders sheet. */
    suspend fun reorderFolders(ordered: List<Folder>) =
        ordered.forEachIndexed { index, folder ->
            folders.setSortKey(folder.id, index.toLong())
        }

    // ── mapping ──────────────────────────────────────────────────────────

    private fun ConversationEntity.toModel(preview: String?) = Conversation(
        id = id,
        title = title,
        titleIsManual = titleIsManual,
        folderId = folderId,
        pinned = pinned,
        preview = preview?.replace('\n', ' ')?.take(80).orEmpty(),
        updatedAt = updatedAt,
    )

    private fun MessageEntity.toModel() = ChatMessage(
        id = id,
        // A row written by a newer version could name a role this build has
        // never heard of. Falling back beats crashing the whole transcript.
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.ASSISTANT),
        text = text,
        toolCalls = toolCallsJson.toToolCalls(),
        error = readError(errorKind, errorDetail),
        createdAt = createdAt,
    )

    // ── errors, to columns and back ──────────────────────────────────────
    //
    // Written out by hand rather than serialised. AiError is a sealed class the
    // app reasons about — whether a failure can be retried is decided by which
    // one it is — so what is stored is the identity of the case, and the mapping
    // back is a `when` the compiler checks. A reflective scheme would survive a
    // rename silently and produce an unreadable row a version later.

    private fun AiError.storageKind(): String = when (this) {
        AiError.NoKey -> "NO_KEY"
        AiError.BadKey -> "BAD_KEY"
        AiError.RateLimited -> "RATE_LIMITED"
        AiError.Offline -> "OFFLINE"
        is AiError.Server -> "SERVER"
        is AiError.Unknown -> "UNKNOWN"
    }

    private fun AiError.storageDetail(): String? = when (this) {
        is AiError.Server -> detail
        is AiError.Unknown -> detail
        else -> null
    }

    /**
     * Rebuild a failure from its columns.
     *
     * An unrecognised kind becomes [AiError.Unknown] rather than null. A row
     * written by a newer build knows something happened, and showing "something
     * went wrong" is honest where showing nothing would put the blank gap back —
     * which is the entire bug this pair of columns exists to fix.
     */
    private fun readError(kind: String?, detail: String?): AiError? = when (kind) {
        null -> null
        "NO_KEY" -> AiError.NoKey
        "BAD_KEY" -> AiError.BadKey
        "RATE_LIMITED" -> AiError.RateLimited
        "OFFLINE" -> AiError.Offline
        "SERVER" -> AiError.Server(detail.orEmpty())
        else -> AiError.Unknown(detail ?: "This reply did not finish.")
    }

    private fun List<ToolCall>.toJsonOrNull(): String? {
        if (isEmpty()) return null
        val array = JSONArray()
        forEach { call ->
            array.put(
                JSONObject()
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("argumentsJson", call.argumentsJson)
                    .put("status", call.status.name)
                    .put("result", call.result ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    private fun String?.toToolCalls(): List<ToolCall> {
        if (this.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(this)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ToolCall(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    argumentsJson = o.optString("argumentsJson"),
                    status = runCatching { ToolCall.Status.valueOf(o.getString("status")) }
                        .getOrDefault(ToolCall.Status.DONE),
                    result = if (o.isNull("result")) null else o.getString("result"),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /**
         * Everything FTS would read as an operator, plus whitespace.
         *
         * Splitting on these rather than deleting them means a search for
         * `warp-toolchain` looks for both halves instead of one nonsense word.
         */
        private val FTS_SPLIT = Regex("""[\s"'*()\[\]:^\-,.;!?]+""")

        const val UNTITLED = "New chat"

        /** How long a deleted conversation can still be brought back. */
        const val UNDO_WINDOW_MS = 15_000L
    }
}

// ── what the UI sees ─────────────────────────────────────────────────────

data class Conversation(
    val id: String,
    val title: String,
    val titleIsManual: Boolean,
    val folderId: String?,
    val pinned: Boolean,
    val preview: String,
    val updatedAt: Long,
)

data class Folder(
    val id: String,
    val name: String,
    val sortKey: Long,
)

/** How a list of found conversations is arranged. */
enum class ConversationOrder(val label: String) {
    /** Newest first. What you want when you half-remember something recent. */
    RECENT("Newest"),

    /** By name. What you want when you know exactly what it is called. */
    ALPHABETICAL("A–Z"),
}

data class DrawerState(
    val conversations: List<Conversation> = emptyList(),
    val folders: List<Folder> = emptyList(),
)
