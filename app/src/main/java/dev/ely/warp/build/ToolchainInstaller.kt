package dev.ely.warp.build

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
    }

    /** Is the bundle present inside this APK? */
    fun isBundled(context: Context): Boolean = runCatching {
        context.assets.open(ASSET_NAME).close()
        true
    }.getOrDefault(false)

    /** What is the current situation, without changing anything? */
    fun currentState(context: Context): State {
        val toolchain = Toolchain.forContext(context)
        return when {
            toolchain.isInstalled -> State.Installed(
                version = toolchain.version(),
                megabytes = directorySize(File(context.filesDir, "toolchain")) / 1_048_576,
            )
            !isBundled(context) -> State.NotBundled
            else -> State.NotInstalled
        }
    }

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
