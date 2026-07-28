package dev.ely.warp.build

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Warp's compiler toolchain, as it lives on the phone.
 *
 * The bundle is built on a PC by `toolchain/build_toolchain.py` and unpacked
 * here into the app's own data directory. See `toolchain/README.md` for how it
 * is assembled and why each piece is there.
 *
 * Layout once installed:
 * ```
 * <filesDir>/toolchain/
 *   MANIFEST.json
 *   bin/aapt2          static arm64 — runs with no setup
 *   bin/zipalign       static arm64
 *   jvm/               OpenJDK 17
 *   lib/               extra .so files the JVM needs
 *   kotlinc/lib        the Kotlin compiler jars
 *   d8/r8.jar          .class -> .dex
 *   platform/android.jar
 * ```
 */
class Toolchain(private val root: File) {

    val manifestFile = File(root, "MANIFEST.json")

    val aapt2 = File(root, "bin/aapt2")
    val zipalign = File(root, "bin/zipalign")
    val java = File(root, "jvm/bin/java")
    val javac = File(root, "jvm/bin/javac")
    val javaHome = File(root, "jvm")
    val androidJar = File(root, "platform/android.jar")
    val r8Jar = File(root, "d8/r8.jar")
    val kotlincLib = File(root, "kotlinc/lib")

    /** Every jar of the Kotlin compiler, for the `-cp` argument. */
    val kotlincJars: List<File>
        get() = kotlincLib.listFiles { f -> f.extension == "jar" }?.sorted() ?: emptyList()

    val isInstalled: Boolean
        get() = manifestFile.isFile && aapt2.isFile && java.isFile

    fun manifest(): JSONObject? =
        runCatching { JSONObject(manifestFile.readText()) }.getOrNull()

    fun version(): String? = manifest()?.optString("bundle_version")?.takeIf { it.isNotEmpty() }

    /**
     * The environment every toolchain process must run with.
     *
     * `LD_LIBRARY_PATH` is the important one. The JDK we ship is a Termux
     * build, so four of its binaries have `/data/data/com.termux/...` baked
     * into their RUNPATH. Android's linker searches `LD_LIBRARY_PATH` *before*
     * RUNPATH, so pointing it at our own folders overrides those stale paths.
     *
     * `TMPDIR` matters too: the JVM wants a writable temp directory, and the
     * usual `/tmp` does not exist on Android.
     */
    fun env(tmpDir: File, home: File): Map<String, String> {
        tmpDir.mkdirs()
        return mapOf(
            "PATH" to listOf(
                File(root, "bin").absolutePath,
                File(root, "jvm/bin").absolutePath,
                "/system/bin",
            ).joinToString(":"),
            "LD_LIBRARY_PATH" to listOf(
                File(root, "jvm/lib").absolutePath,
                File(root, "jvm/lib/server").absolutePath,
                File(root, "lib").absolutePath,
            ).joinToString(":"),
            "JAVA_HOME" to javaHome.absolutePath,
            "HOME" to home.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
        )
    }

    /** Arguments to launch a jar-based tool on the bundled JVM. */
    fun javaCommand(heapMb: Int, classpath: List<File>, mainClass: String, args: List<String>) =
        buildList {
            add(java.absolutePath)
            add("-Xmx${heapMb}m")
            // Keep the JVM's own temp files inside our sandbox.
            add("-Djava.io.tmpdir=${File(root.parentFile, "build-tmp").absolutePath}")
            // The compiler is short-lived; a simpler GC starts faster.
            add("-XX:+UseSerialGC")
            add("-cp")
            add(classpath.joinToString(":") { it.absolutePath })
            add(mainClass)
            addAll(args)
        }

    companion object {
        private const val TAG = "WarpToolchain"

        /** Paths inside the bundle that must end up executable. */
        private fun shouldBeExecutable(relativePath: String) =
            relativePath.startsWith("bin/") || relativePath.startsWith("jvm/bin/")

        fun forContext(context: Context) = Toolchain(File(context.filesDir, "toolchain"))

        /**
         * Unpack a bundle zip into [target].
         *
         * Any previous install is deleted first: a half-replaced toolchain is
         * far worse to debug than a missing one.
         *
         * @param onProgress called with (filesDone, bytesDone)
         */
        suspend fun install(
            source: InputStream,
            target: File,
            onProgress: ((Int, Long) -> Unit)? = null,
        ): Result<Toolchain> = withContext(Dispatchers.IO) {
            runCatching {
                if (target.exists()) {
                    Log.i(TAG, "removing previous install at $target")
                    target.deleteRecursively()
                }
                target.mkdirs()

                var files = 0
                var bytes = 0L
                val buffer = ByteArray(64 * 1024)

                ZipInputStream(source.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val outFile = File(target, entry.name)

                        // Zip-slip guard: an entry named "../../x" would
                        // otherwise write outside the toolchain directory.
                        val canonicalTarget = target.canonicalPath + File.separator
                        if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                            error("zip entry escapes the target directory: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().buffered().use { out ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    bytes += n
                                }
                            }
                            if (shouldBeExecutable(entry.name)) {
                                if (!outFile.setExecutable(true, true)) {
                                    Log.w(TAG, "could not chmod +x ${entry.name}")
                                }
                            }
                            files++
                            if (files % 25 == 0) onProgress?.invoke(files, bytes)
                        }
                        zip.closeEntry()
                    }
                }

                onProgress?.invoke(files, bytes)
                Log.i(TAG, "installed $files files, ${bytes / 1048576} MB into $target")

                val toolchain = Toolchain(target)
                check(toolchain.isInstalled) {
                    "bundle unpacked but looks incomplete — MANIFEST.json, aapt2 or java missing"
                }
                toolchain
            }
        }
    }
}
