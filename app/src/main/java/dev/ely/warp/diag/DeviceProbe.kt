package dev.ely.warp.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.StatFs
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Step 0 pre-flight checks.
 *
 * Warp's entire design rests on one assumption: that we can write a binary into
 * the app's own data directory, mark it executable, and run it. Apps targeting
 * API 29+ cannot — which is why Warp targets 28.
 *
 * [canExecFromDataDir] tests that assumption for real, on the actual device,
 * before we spend anything on a 350 MB toolchain.
 */
object DeviceProbe {

    data class Check(
        val label: String,
        val value: String,
        val status: Status,
        val detail: String? = null,
    )

    enum class Status { PASS, WARN, FAIL, INFO }

    private const val TAG = "WarpPreflight"

    fun runAll(context: Context): List<Check> {
        val checks = buildList {
            add(abi())
            add(androidVersion())
            add(ram(context))
            add(heap(context))
            add(storage(context))
            add(canExecFromDataDir(context))
        }
        // Also mirrored to logcat so the results are readable over adb, without
        // needing eyes on the screen.
        Log.i(TAG, "───── Warp Step 0 pre-flight ─────")
        checks.forEach {
            Log.i(TAG, "[${it.status}] ${it.label}: ${it.value}")
            it.detail?.let { d -> Log.i(TAG, "        $d") }
        }
        val exec = checks.firstOrNull { it.label == "Execute from app storage" }
        Log.i(TAG, "VERDICT: ${if (exec?.status == Status.PASS) "EXEC_ALLOWED" else "EXEC_BLOCKED"}")
        Log.i(TAG, "──────────────────────────────────")
        return checks
    }

    // ── Architecture ─────────────────────────────────────────────────────
    private fun abi(): Check {
        val abis = Build.SUPPORTED_ABIS.toList()
        val is64 = abis.contains("arm64-v8a")
        return Check(
            label = "CPU architecture",
            value = abis.firstOrNull() ?: "unknown",
            status = if (is64) Status.PASS else Status.FAIL,
            detail = if (is64) {
                "arm64-v8a — our toolchain targets this"
            } else {
                "Warp's toolchain is arm64-only. Supported here: $abis"
            },
        )
    }

    // ── OS ───────────────────────────────────────────────────────────────
    private fun androidVersion() = Check(
        label = "Android version",
        value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        status = Status.INFO,
        detail = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    // ── Memory ───────────────────────────────────────────────────────────
    private fun ram(context: Context): Check {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / 1_073_741_824.0
        val availGb = info.availMem / 1_073_741_824.0
        val status = when {
            totalGb >= 7.5 -> Status.PASS
            totalGb >= 5.5 -> Status.WARN
            else -> Status.FAIL
        }
        return Check(
            label = "Device RAM",
            value = "%.1f GB total · %.1f GB free".format(totalGb, availGb),
            status = status,
            detail = when (status) {
                Status.PASS -> "Comfortable for the Kotlin compiler"
                Status.WARN -> "Workable, but Kotlin builds may be slow"
                else -> "Below the 6 GB minimum for on-device Kotlin builds"
            },
        )
    }

    /**
     * Android caps each app's Java heap. The Kotlin compiler needs far more than
     * that — which is exactly why we run it as a *separate process* with its own
     * -Xmx rather than inside Warp.
     */
    private fun heap(context: Context): Check {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val largeHeapMb = am.largeMemoryClass
        val usedMb = Debug.getNativeHeapAllocatedSize() / 1_048_576
        return Check(
            label = "App heap limit",
            value = "$largeHeapMb MB (largeHeap)",
            status = Status.INFO,
            detail = "kotlinc runs in its own process, so this limit " +
                "doesn't constrain it. Native heap in use: $usedMb MB",
        )
    }

    // ── Storage ──────────────────────────────────────────────────────────
    private fun storage(context: Context): Check {
        val stat = StatFs(context.filesDir.absolutePath)
        val freeGb = stat.availableBytes / 1_073_741_824.0
        val status = when {
            freeGb >= 2.0 -> Status.PASS
            freeGb >= 1.0 -> Status.WARN
            else -> Status.FAIL
        }
        return Check(
            label = "Free storage",
            value = "%.1f GB".format(freeGb),
            status = status,
            detail = "Toolchain needs ~350 MB unpacked, plus room for projects",
        )
    }

    // ── THE decisive check ───────────────────────────────────────────────
    /**
     * Copies a real ELF binary (the system shell) into our data directory,
     * marks it executable, and runs it.
     *
     * If this passes, `targetSdk 28` did its job and Warp can run its own
     * compiler toolchain. If it fails, the whole on-device build approach needs
     * the `jniLibs` fallback described in the plan.
     */
    fun canExecFromDataDir(context: Context): Check {
        val label = "Execute from app storage"
        val target = File(context.filesDir, "exec_probe")

        return try {
            val systemSh = File("/system/bin/sh")
            if (!systemSh.exists()) {
                return Check(label, "inconclusive", Status.WARN, "/system/bin/sh not found")
            }

            systemSh.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }

            val madeExecutable = target.setExecutable(true, true)
            if (!madeExecutable) {
                return Check(
                    label, "BLOCKED", Status.FAIL,
                    "chmod +x was refused on ${target.absolutePath}",
                )
            }

            val process = ProcessBuilder(target.absolutePath, "-c", "echo WARP_EXEC_OK")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()

            if (output.contains("WARP_EXEC_OK")) {
                Check(
                    label, "ALLOWED", Status.PASS,
                    "Ran an ELF binary from ${context.filesDir.name}/ — " +
                        "the toolchain will work. targetSdk 28 confirmed.",
                )
            } else {
                Check(
                    label, "BLOCKED", Status.FAIL,
                    "Process started but produced: \"$output\" (exit ${process.exitValue()})",
                )
            }
        } catch (e: Exception) {
            Check(
                label, "BLOCKED", Status.FAIL,
                "${e.javaClass.simpleName}: ${e.message} — " +
                    "W^X is being enforced. Fall back to jniLibs (.so) packaging.",
            )
        } finally {
            runCatching { target.delete() }
        }
    }
}
