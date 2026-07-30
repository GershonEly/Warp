package dev.ely.warp

import android.app.Application
import android.util.Log
import dev.ely.warp.data.ConversationRepository
import dev.ely.warp.debug.DebugBridge
import dev.ely.warp.debug.DebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WarpApplication : Application() {

    /**
     * Work that outlives any one screen.
     *
     * A [SupervisorJob] so one failed background task cannot take the others
     * down with it — the startup sweep failing must not stop anything else the
     * app wants to do off the main thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The conversation store, opened once for the whole process.
     *
     * It lives here rather than in the composition because the database
     * outlives the UI, and because two handles to the same SQLite file is a
     * corruption story waiting to happen.
     */
    val conversations: ConversationRepository by lazy { ConversationRepository(this) }

    override fun onCreate() {
        super.onCreate()

        // Anything whose undo window closed in a previous session is cleared
        // here, at startup, rather than on a timer. A timer only fires if the
        // process is still alive, and the one thing a phone can be relied upon
        // to do is kill your process — so the sweep has to happen on the way in.
        scope.launch {
            runCatching { conversations.purgeOldDeletes(System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "could not sweep deleted conversations", it) }
        }

        // The debug surface follows the key rather than the launch. Typing a key
        // opens it, clearing the key shuts it, and a fresh install has no key —
        // so it has never been open. In a release build this whole branch calls
        // a stub, because the server is not compiled into that build at all.
        if (DebugServer.IS_SUPPORTED) {
            DebugBridge.load(this)
            scope.launch {
                DebugBridge.key.collect { key ->
                    if (key == null) DebugServer.stop() else DebugServer.start(this@WarpApplication)
                }
            }
        }
    }

    private companion object {
        const val TAG = "WarpApp"
    }
}
