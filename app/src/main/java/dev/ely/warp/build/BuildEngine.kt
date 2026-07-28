package dev.ely.warp.build

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Compiles an Android project into an APK, entirely on the phone.
 *
 * The pipeline mirrors what Gradle does on a desktop, minus Gradle:
 *
 * ```
 * 1. aapt2 compile   res/           -> compiled resources
 * 2. aapt2 link      + manifest     -> base APK (resources only) + R.java
 * 3. javac           R.java         -> R.class      (Kotlin needs it on the classpath)
 * 4. kotlinc         Kotlin sources -> .class
 * 5. d8              .class         -> classes.dex
 * 6. repack          dex into APK
 * 7. zipalign        aligned APK
 * 8. sign            installable APK
 * ```
 *
 * Stage 8 runs only when a [signer] is supplied. Without one the build stops
 * at an aligned but unsigned APK, which Android will refuse to install.
 *
 * Each stage is a separate process, so a compiler crash cannot take Warp down
 * with it, and `kotlinc` gets its own heap rather than fighting Android's
 * per-app memory cap.
 *
 * Expected project layout — Warp generates these itself, so it can be strict:
 * ```
 * <project>/
 *   AndroidManifest.xml
 *   res/           optional
 *   src/           *.kt
 * ```
 */
class BuildEngine(
    private val toolchain: Toolchain,
    private val workRoot: File,
    private val signer: ApkSigner? = null,
) {

    enum class Stage(val label: String) {
        PREPARE("Preparing"),
        COMPILE_RESOURCES("Compiling resources"),
        LINK_RESOURCES("Linking resources"),
        COMPILE_R("Compiling R class"),
        COMPILE_KOTLIN("Compiling Kotlin"),
        DEX("Converting to DEX"),
        PACKAGE("Packaging APK"),
        ALIGN("Aligning APK"),
        SIGN("Signing APK"),
    }

    data class Request(
        val projectDir: File,
        val applicationId: String,
        val minSdk: Int = 28,
        val targetSdk: Int = 28,
        /** Heap for the Kotlin compiler. It runs out-of-process, so this is not
         *  limited by Android's per-app cap. */
        val kotlincHeapMb: Int = 1024,
    )

    data class StageResult(
        val stage: Stage,
        val ok: Boolean,
        val durationMs: Long,
        val output: String,
    )

    sealed interface Outcome {
        data class Success(
            val apk: File,
            /** False when no signer was supplied — the APK will not install. */
            val signed: Boolean,
            val stages: List<StageResult>,
            val totalMs: Long,
        ) : Outcome

        data class Failure(
            val stage: Stage,
            val message: String,
            val output: String,
            val stages: List<StageResult>,
            val totalMs: Long,
        ) : Outcome
    }

    /**
     * Run the whole pipeline.
     *
     * @param onStage called when each stage begins, for the UI
     * @param onLine  called for every line of compiler output, for the live log
     */
    suspend fun build(
        request: Request,
        onStage: ((Stage) -> Unit)? = null,
        onLine: ((ProcessRunner.Line) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val stages = mutableListOf<StageResult>()

        // Scratch space, wiped for every build. Incremental builds come later;
        // correctness first.
        val work = File(workRoot, "build").apply {
            deleteRecursively()
            mkdirs()
        }
        val tmp = File(workRoot, "build-tmp").apply { mkdirs() }
        val env = toolchain.env(tmpDir = tmp, home = workRoot)

        val compiledRes = File(work, "res.zip")
        val genDir = File(work, "gen").apply { mkdirs() }
        val classesDir = File(work, "classes").apply { mkdirs() }
        val dexDir = File(work, "dex").apply { mkdirs() }
        val baseApk = File(work, "base.apk")
        val packagedApk = File(work, "packaged.apk")
        val alignedApk = File(work, "${request.applicationId}-unsigned.apk")

        suspend fun stage(
            s: Stage,
            timeoutMinutes: Long,
            command: List<String>,
        ): StageResult {
            onStage?.invoke(s)
            Log.i(TAG, "stage ${s.name}")
            val r = ProcessRunner.run(
                command = command,
                workingDir = request.projectDir,
                env = env,
                timeout = timeoutMinutes,
                timeoutUnit = TimeUnit.MINUTES,
                onLine = onLine,
            )
            val result = StageResult(s, r.ok, r.durationMs, r.output)
            stages += result
            return result
        }

        fun fail(s: Stage, message: String, output: String = "") = Outcome.Failure(
            stage = s,
            message = message,
            output = output,
            stages = stages.toList(),
            totalMs = System.currentTimeMillis() - started,
        )

        // ── 0. sanity ────────────────────────────────────────────────────
        onStage?.invoke(Stage.PREPARE)
        if (!toolchain.isInstalled) {
            return@withContext fail(Stage.PREPARE, "The toolchain is not installed yet.")
        }
        val manifest = File(request.projectDir, "AndroidManifest.xml")
        if (!manifest.isFile) {
            return@withContext fail(Stage.PREPARE, "AndroidManifest.xml not found in the project.")
        }
        val srcDir = File(request.projectDir, "src")
        val ktFiles = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        if (ktFiles.isEmpty()) {
            return@withContext fail(Stage.PREPARE, "No .kt source files found in src/.")
        }
        val resDir = File(request.projectDir, "res")
        val hasRes = resDir.isDirectory && (resDir.listFiles()?.isNotEmpty() == true)

        // ── 1. compile resources ─────────────────────────────────────────
        if (hasRes) {
            val r = stage(
                Stage.COMPILE_RESOURCES, timeoutMinutes = 5,
                command = listOf(
                    toolchain.aapt2.absolutePath, "compile",
                    "--dir", resDir.absolutePath,
                    "-o", compiledRes.absolutePath,
                ),
            )
            if (!r.ok) return@withContext fail(Stage.COMPILE_RESOURCES, "aapt2 could not compile the resources.", r.output)
        }

        // ── 2. link resources ────────────────────────────────────────────
        run {
            val cmd = buildList {
                add(toolchain.aapt2.absolutePath); add("link")
                add("-I"); add(toolchain.androidJar.absolutePath)
                add("--manifest"); add(manifest.absolutePath)
                add("--java"); add(genDir.absolutePath)
                add("--min-sdk-version"); add(request.minSdk.toString())
                add("--target-sdk-version"); add(request.targetSdk.toString())
                add("--auto-add-overlay")
                add("-o"); add(baseApk.absolutePath)
                if (hasRes) add(compiledRes.absolutePath)
            }
            val r = stage(Stage.LINK_RESOURCES, timeoutMinutes = 5, command = cmd)
            if (!r.ok) return@withContext fail(Stage.LINK_RESOURCES, "aapt2 could not link the resources.", r.output)
        }

        // ── 3. compile R.java ────────────────────────────────────────────
        // aapt2 emits R as Java. Kotlin code refers to R, so it has to exist as
        // a compiled class before kotlinc runs.
        val rJavaFiles = genDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        if (rJavaFiles.isNotEmpty()) {
            val r = stage(
                Stage.COMPILE_R, timeoutMinutes = 5,
                command = buildList {
                    add(toolchain.javac.absolutePath)
                    add("-nowarn")
                    add("-cp"); add(toolchain.androidJar.absolutePath)
                    add("-d"); add(classesDir.absolutePath)
                    addAll(rJavaFiles.map { it.absolutePath })
                },
            )
            if (!r.ok) return@withContext fail(Stage.COMPILE_R, "Could not compile the generated R class.", r.output)
        }

        // ── 4. compile Kotlin ────────────────────────────────────────────
        run {
            val compilerJars = toolchain.kotlincJars
            if (compilerJars.isEmpty()) {
                return@withContext fail(Stage.COMPILE_KOTLIN, "The Kotlin compiler jars are missing from the toolchain.")
            }
            val classpath = listOf(toolchain.androidJar, classesDir) + stdlibJars()
            val r = stage(
                Stage.COMPILE_KOTLIN, timeoutMinutes = 20,
                command = toolchain.javaCommand(
                    heapMb = request.kotlincHeapMb,
                    classpath = compilerJars,
                    mainClass = KOTLIN_COMPILER_MAIN,
                    args = buildList {
                        add("-no-reflect")
                        add("-nowarn")
                        add("-jvm-target"); add("17")
                        add("-classpath"); add(classpath.joinToString(":") { it.absolutePath })
                        add("-d"); add(classesDir.absolutePath)
                        addAll(ktFiles.map { it.absolutePath })
                    },
                ),
            )
            if (!r.ok) return@withContext fail(Stage.COMPILE_KOTLIN, "Kotlin compilation failed.", r.output)
        }

        // ── 5. dex ───────────────────────────────────────────────────────
        run {
            val classFiles = classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.absolutePath }
                .toList()
            if (classFiles.isEmpty()) {
                return@withContext fail(Stage.DEX, "The Kotlin compiler produced no .class files.")
            }
            val r = stage(
                Stage.DEX, timeoutMinutes = 15,
                command = toolchain.javaCommand(
                    heapMb = 1024,
                    classpath = listOf(toolchain.r8Jar),
                    mainClass = D8_MAIN,
                    args = buildList {
                        add("--lib"); add(toolchain.androidJar.absolutePath)
                        add("--min-api"); add(request.minSdk.toString())
                        add("--output"); add(dexDir.absolutePath)
                        // The Kotlin runtime has to ship inside the app.
                        stdlibJars().forEach { add(it.absolutePath) }
                        addAll(classFiles)
                    },
                ),
            )
            if (!r.ok) return@withContext fail(Stage.DEX, "d8 could not convert the classes to DEX.", r.output)
        }

        // ── 6. package ───────────────────────────────────────────────────
        onStage?.invoke(Stage.PACKAGE)
        val packStarted = System.currentTimeMillis()
        val dexFiles = dexDir.listFiles { f -> f.extension == "dex" }?.sorted().orEmpty()
        if (dexFiles.isEmpty()) {
            return@withContext fail(Stage.PACKAGE, "d8 produced no .dex files.")
        }
        runCatching { addDexToApk(baseApk, dexFiles, packagedApk) }
            .onFailure { return@withContext fail(Stage.PACKAGE, "Could not write the APK: ${it.message}") }
        stages += StageResult(
            Stage.PACKAGE, true, System.currentTimeMillis() - packStarted,
            "added ${dexFiles.size} dex file(s)",
        )

        // ── 7. align ─────────────────────────────────────────────────────
        run {
            val r = stage(
                Stage.ALIGN, timeoutMinutes = 5,
                command = listOf(
                    toolchain.zipalign.absolutePath,
                    "-f", "-p", "4",
                    packagedApk.absolutePath,
                    alignedApk.absolutePath,
                ),
            )
            if (!r.ok) return@withContext fail(Stage.ALIGN, "zipalign failed.", r.output)
        }

        // ── 8. sign ──────────────────────────────────────────────────────
        // Android will not install an unsigned APK. This runs in-process on
        // ART rather than as a subprocess: apksig is bundled into Warp, and
        // signing is hashing rather than compiling, so it is cheap.
        var finalApk = alignedApk
        var signed = false
        if (signer != null) {
            onStage?.invoke(Stage.SIGN)
            val signedApk = File(work, "${request.applicationId}.apk")
            when (val s = signer.sign(alignedApk, signedApk, request.minSdk)) {
                is ApkSigner.Outcome.Success -> {
                    stages += StageResult(Stage.SIGN, true, s.durationMs, "signed with the debug key")
                    finalApk = s.signedApk
                    signed = true
                }
                is ApkSigner.Outcome.Failure ->
                    return@withContext fail(Stage.SIGN, s.message, s.detail)
            }
        } else {
            Log.w(TAG, "no signer supplied — APK is unsigned and will not install")
        }

        Log.i(TAG, "build finished: ${finalApk.length() / 1024} KB, signed=$signed")
        Outcome.Success(
            apk = finalApk,
            signed = signed,
            stages = stages.toList(),
            totalMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * The Kotlin runtime jars that must end up inside the built app.
     *
     * Filtered by name because `kotlinc/lib` also holds compiler-only jars,
     * and shipping those would bloat the APK for no reason.
     */
    private fun stdlibJars(): List<File> =
        toolchain.kotlincJars.filter { it.name in RUNTIME_JARS }

    /**
     * Copy [baseApk] to [out], adding the dex files.
     *
     * aapt2 produces an APK holding only resources; the code has to be added
     * afterwards. Done with plain zip operations — no extra tool needed.
     */
    private fun addDexToApk(baseApk: File, dexFiles: List<File>, out: File) {
        if (out.exists()) out.delete()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            ZipFile(baseApk).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.name.endsWith(".dex")) continue  // never keep a stale dex
                    zos.putNextEntry(ZipEntry(entry.name).apply {
                        // Fixed time so repeated builds of unchanged input match.
                        time = FIXED_ENTRY_TIME
                    })
                    zip.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            dexFiles.forEachIndexed { index, dex ->
                // d8 names them classes.dex, classes2.dex, ... and that naming
                // is what the Android runtime looks for.
                val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                zos.putNextEntry(ZipEntry(name).apply { time = FIXED_ENTRY_TIME })
                dex.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    companion object {
        private const val TAG = "WarpBuild"

        private const val KOTLIN_COMPILER_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        private const val D8_MAIN = "com.android.tools.r8.D8"

        /** 1 Jan 2000, arbitrary but constant. */
        private const val FIXED_ENTRY_TIME = 946684800000L

        private val RUNTIME_JARS = setOf(
            "kotlin-stdlib.jar",
            "annotations-13.0.jar",
        )
    }
}
