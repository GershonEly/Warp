package dev.ely.warp.build

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gets the toolchain from the APK onto the phone.
 *
 * The bundle ships as a single asset, `toolchain.zip`, packed in by the
 * `syncToolchainAsset` Gradle task. It is stored uncompressed in the APK (see
 * `androidResources.noCompress`), so it can be streamed straight out rather
 * than being copied to a temporary file first.
 */
object ToolchainInstaller {

    private const val TAG = "WarpInstaller"
    const val ASSET_NAME = "toolchain.zip"

    sealed interface State {
        data object Checking : State

        /** The APK was built without a toolchain asset. */
        data object NotBundled : State

        data object NotInstalled : State

        data class Installing(val files: Int, val megabytes: Long) : State

        data class Installed(val version: String?, val megabytes: Long) : State

        data class Failed(val message: String, val detail: String = "") : State

        /**
         * There is not enough room to unpack it.
         *
         * Its own state rather than a [Failed], because it is the one failure
         * the person can actually do something about, and the thing to do is
         * not "try again" — it is "free up some space first".
         */
        data class NoRoom(val neededMb: Long, val freeMb: Long) : State
    }

    /** Roughly what the unpacked toolchain comes to, plus room to work in. */
    private const val NEEDED_MB = 400L

    // ── setting itself up ────────────────────────────────────────────────

    /**
     * What the whole app can watch, rather than what one screen happens to know.
     *
     * **This is the bug this state exists to fix.** Installing the toolchain
     * used to be reachable only from the Build tab, behind a button nobody is
     * told about. Somebody who opened Warp, asked for an app and waited got a
     * build that failed for a reason named nowhere they would look — it
     * happened on a second person's phone within a day of them being given it,
     * and neither they nor the person who gave it to them could tell why.
     *
     * The compiler is not a feature you opt into. It is the thing the app is
     * for, and it ships inside the APK — so it sets itself up.
     */
    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    /** One install at a time, however many callers ask. */
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Make sure the toolchain is there, starting the work if it is not.
     *
     * Safe to call from anywhere, as often as you like: already-installed
     * returns immediately and a second caller during an install joins the first
     * rather than starting a competing unpack of 300 MB.
     *
     * Deliberately **not** blocking and deliberately not fatal. Warp is still
     * worth opening without a compiler — you can read, plan and write code —
     * so this runs behind the app rather than in front of it.
     */
    fun ensureInstalled(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                val now = currentState(app)
                _state.value = now
                if (now !is State.NotInstalled) return@withLock

                // Checked before a single byte is written. Running out of room
                // halfway leaves a half-unpacked toolchain, and the install
                // path already treats that as the worst outcome of the three.
                val free = freeMegabytes(app)
                if (free < NEEDED_MB) {
                    _state.value = State.NoRoom(NEEDED_MB, free)
                    Log.w(TAG, "not installing: $free MB free, needs $NEEDED_MB MB")
                    return@withLock
                }

                _state.value = install(app) { _state.value = it }
            }
        }
    }

    /** Try again after a failure, or after room has been made. */
    fun retry(context: Context) {
        _state.value = State.Checking
        ensureInstalled(context)
    }

    /** Keep [state] in step when something else changes the situation. */
    fun refresh(context: Context) {
        _state.value = currentState(context.applicationContext)
    }

    private fun freeMegabytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes / 1_048_576
    }.getOrDefault(Long.MAX_VALUE)  // Unknown is not a reason to refuse.

    /** Is the bundle present inside this APK? */
    fun isBundled(context: Context): Boolean = runCatching {
        context.assets.open(ASSET_NAME).close()
        true
    }.getOrDefault(false)

    /** What is the current situation, without changing anything? */
    fun currentState(context: Context): State {
        val toolchain = Toolchain.forContext(context)
        return when {
            // Stale counts as not installed, which is the whole point of the
            // check: what is on disk is a *copy* of what was in the APK, and
            // once the APK moves on, the copy is a previous version of the
            // compiler with no way to say so.
            toolchain.isInstalled && !isStale(context) -> State.Installed(
                version = toolchain.version(),
                megabytes = directorySize(File(context.filesDir, "toolchain")) / 1_048_576,
            )
            !isBundled(context) -> State.NotBundled
            else -> State.NotInstalled
        }
    }

    /**
     * Was this toolchain unpacked from an older APK than the one running?
     *
     * **The same silence as the bug this file was just fixed for**, one level
     * up. Installing Warp again with a newer bundle would leave the previous
     * compiler unpacked and in use for ever: `isInstalled` only asks whether
     * the files exist, and they do. The Compose kit arriving in a new bundle is
     * the first time it would have mattered, and it would have looked like
     * Compose simply not working.
     *
     * Keyed on **when the app was last updated**, not on the bundle's own
     * version string. The version in `MANIFEST.json` is written by hand and did
     * not change when the Compose kit was added to it — so a version check
     * would have sailed straight past exactly the case it exists for. Install
     * time is recorded by Android, cannot be forgotten, and changes on every
     * update including a reinstall of the same version.
     */
    private fun isStale(context: Context): Boolean {
        val now = lastUpdateTime(context)
        // Android would not say when it was updated. Not knowing is not a
        // reason to throw away 300 MB somebody already has.
        if (now == 0L) return false
        // **No stamp means unknown, and unknown means replace it.** Every
        // toolchain unpacked before this check existed is in that state,
        // including one that predates the Compose kit — and "probably fine" is
        // the assumption that made a missing compiler invisible in the first
        // place. Costs one re-unpack, once, on installs that already exist.
        return stamp(context) != now
    }

    private fun lastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun stampFile(context: Context) = File(context.filesDir, "toolchain/.installed-from")

    private fun stamp(context: Context): Long =
        runCatching { stampFile(context).readText().trim().toLong() }.getOrDefault(0L)

    /**
     * Unpack the bundled toolchain into the app's data directory.
     *
     * Takes a while — around 300 MB is written — so [onProgress] is called as
     * it goes, and the caller should keep the UI responsive.
     */
    suspend fun install(
        context: Context,
        onProgress: (State) -> Unit = {},
    ): State = withContext(Dispatchers.IO) {
        if (!isBundled(context)) {
            Log.w(TAG, "no $ASSET_NAME asset in this APK")
            return@withContext State.NotBundled
        }

        onProgress(State.Installing(0, 0))
        val target = File(context.filesDir, "toolchain")

        val result = context.assets.open(ASSET_NAME).use { input ->
            Toolchain.install(input, target) { files, bytes ->
                onProgress(State.Installing(files, bytes / 1_048_576))
            }
        }

        result.fold(
            onSuccess = { toolchain ->
                val size = directorySize(target) / 1_048_576
                // Which APK this copy came out of. Written last, after the
                // unpack has succeeded, so a half-unpacked toolchain is never
                // stamped as current.
                runCatching {
                    stampFile(context).writeText(lastUpdateTime(context).toString())
                }
                Log.i(TAG, "toolchain ${toolchain.version()} installed, $size MB")
                State.Installed(toolchain.version(), size)
            },
            onFailure = { error ->
                Log.e(TAG, "install failed", error)
                // A half-written toolchain is worse than none: it would fail
                // later in a much more confusing way.
                runCatching { target.deleteRecursively() }
                State.Failed(
                    "Could not unpack the toolchain.",
                    "${error.javaClass.simpleName}: ${error.message}",
                )
            },
        )
    }

    /** Remove the installed toolchain, freeing roughly 300 MB. */
    suspend fun uninstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        File(context.filesDir, "toolchain").deleteRecursively()
    }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
