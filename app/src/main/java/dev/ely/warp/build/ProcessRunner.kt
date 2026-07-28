package dev.ely.warp.build

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a native or Java process from Warp's own data directory.
 *
 * This is the piece the whole build rests on. Warp targets API 28 precisely so
 * that it may execute files it has written itself — [dev.ely.warp.diag.DeviceProbe]
 * proves that works on the device before we get here.
 */
object ProcessRunner {

    private const val TAG = "WarpProcess"

    /** One line of process output, tagged with which stream it came from. */
    data class Line(val text: String, val isError: Boolean)

    data class Result(
        val exitCode: Int,
        val lines: List<Line>,
        val durationMs: Long,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut

        /** Everything the process printed, both streams, in arrival order. */
        val output: String get() = lines.joinToString("\n") { it.text }

        val errorOutput: String
            get() = lines.filter { it.isError }.joinToString("\n") { it.text }
    }

    /**
     * Run [command] and wait for it to finish.
     *
     * Both output streams are drained on their own threads. That is not
     * optional: a process whose pipe buffer fills up blocks forever, and a
     * compiler is more than capable of filling it.
     *
     * Cancelling the calling coroutine kills the process.
     *
     * @param onLine called for each line as it arrives, for live build logs
     */
    suspend fun run(
        command: List<String>,
        workingDir: File,
        env: Map<String, String>,
        timeout: Long = 10,
        timeoutUnit: TimeUnit = TimeUnit.MINUTES,
        onLine: ((Line) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "command must not be empty" }

        val started = System.currentTimeMillis()
        Log.i(TAG, "exec: ${command.joinToString(" ")}")

        val builder = ProcessBuilder(command).directory(workingDir)
        builder.environment().apply {
            // Start from a clean slate. Whatever Android handed us is not the
            // environment the toolchain expects, and stray vars cause confusing
            // failures that are hard to reproduce.
            clear()
            putAll(env)
        }

        val process = try {
            builder.start()
        } catch (e: Exception) {
            // The most common cause here is a missing +x bit on the binary.
            return@withContext Result(
                exitCode = -1,
                lines = listOf(Line("failed to start: ${e.javaClass.simpleName}: ${e.message}", true)),
                durationMs = System.currentTimeMillis() - started,
            )
        }

        val lines = mutableListOf<Line>()
        val lock = Any()

        fun drain(stream: InputStream, isError: Boolean) = thread(isDaemon = true) {
            runCatching {
                stream.bufferedReader().useLines { seq ->
                    seq.forEach { text ->
                        val line = Line(text, isError)
                        synchronized(lock) { lines += line }
                        onLine?.invoke(line)
                    }
                }
            }
        }

        val outThread = drain(process.inputStream, isError = false)
        val errThread = drain(process.errorStream, isError = true)

        var timedOut = false
        try {
            // Poll rather than block, so coroutine cancellation is honoured
            // promptly instead of only after the process exits.
            val deadline = System.nanoTime() + timeoutUnit.toNanos(timeout)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() > deadline) {
                    timedOut = true
                    Log.w(TAG, "timeout after $timeout $timeoutUnit — killing")
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                    break
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "cancelled — killing process")
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            throw e
        }

        // Give the readers a moment to flush whatever is still buffered,
        // otherwise the tail of a failing command's error message is lost.
        outThread.join(2_000)
        errThread.join(2_000)

        val exit = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1)
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "exit=$exit in ${elapsed}ms (${lines.size} lines)")

        Result(
            exitCode = exit,
            lines = synchronized(lock) { lines.toList() },
            durationMs = elapsed,
            timedOut = timedOut,
        )
    }

    /** Convenience for a quick probe such as `java -version`. */
    suspend fun capture(
        command: List<String>,
        workingDir: File,
        env: Map<String, String>,
        timeout: Long = 60,
        timeoutUnit: TimeUnit = TimeUnit.SECONDS,
    ): Result = run(command, workingDir, env, timeout, timeoutUnit)
}
