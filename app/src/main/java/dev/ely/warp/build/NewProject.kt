package dev.ely.warp.build

import org.json.JSONObject
import java.io.File

/**
 * Turns an empty folder into something a compiler will accept.
 *
 * Until now the model could write `Counter.kt` into the project and nothing on
 * earth could build it: [BuildEngine] wants `AndroidManifest.xml`, `res/` and
 * `src/`, and a folder with one loose Kotlin file in it has none of them. Every
 * build would have answered "no AndroidManifest.xml", which is a true answer to
 * a question nobody meant to ask.
 *
 * The shape is copied from [SampleProject] on purpose — that exact layout is the
 * one proven to compile on the phone, and inventing a second one would mean two
 * layouts and only one of them tested.
 *
 * **It never deletes.** [SampleProject.write] begins with `deleteRecursively`,
 * which is right for a throwaway sample and catastrophic for somebody's work.
 * This refuses instead.
 */
object NewProject {

    /** What Warp remembers about a project, kept beside it. */
    data class Meta(val name: String, val applicationId: String)

    private const val META_FILE = "warp.json"

    /** True once this folder holds a project. */
    fun exists(dir: File): Boolean = File(dir, "AndroidManifest.xml").isFile

    /**
     * What is here, or null if this is not a project.
     *
     * Read from a file rather than guessed from the manifest, because the app id
     * has to survive the model editing the manifest by hand — and it will.
     */
    fun meta(dir: File): Meta? {
        val json = runCatching { JSONObject(File(dir, META_FILE).readText()) }.getOrNull()
            ?: return null
        val id = json.optString("applicationId").takeIf { it.isNotBlank() } ?: return null
        return Meta(json.optString("name").ifBlank { id.substringAfterLast('.') }, id)
    }

    /**
     * Remember where the last build landed.
     *
     * Recorded rather than recomputed. `install` guessed the path from the work
     * root and the application id, and guessed wrong — the engine puts it in a
     * `build/` subfolder — so install answered "nothing built yet" seconds
     * after a build succeeded. Two places knowing the same path is one place too
     * many, and the one that is wrong is always the one nobody ran.
     */
    fun recordBuild(dir: File, apk: File) {
        val json = runCatching { JSONObject(File(dir, META_FILE).readText()) }
            .getOrDefault(JSONObject())
        File(dir, META_FILE).writeText(json.put("lastApk", apk.absolutePath).toString())
    }

    /**
     * The APK from the last successful build, or null if there has not been one.
     *
     * Null when the file has since been deleted, too — a remembered path to
     * something that is gone is worse than no memory at all.
     */
    fun lastApk(dir: File): File? {
        val json = runCatching { JSONObject(File(dir, META_FILE).readText()) }.getOrNull()
            ?: return null
        val path = json.optString("lastApk").takeIf { it.isNotBlank() } ?: return null
        return File(path).takeIf { it.isFile }
    }

    /**
     * Create a project, or explain why not.
     *
     * @param applicationId blank to derive one from [name].
     * @return the failure reason, or null when it worked.
     */
    fun create(dir: File, name: String, applicationId: String = ""): String? {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return "the app needs a name"
        if (exists(dir)) {
            // Names what is in the way. "Already exists" leaves you guessing
            // whether it means the folder, the manifest, or the whole idea.
            return "there is already a project here (${meta(dir)?.name ?: "unnamed"})"
        }

        val id = applicationId.trim().ifBlank { derivePackage(cleanName) }
        validatePackage(id)?.let { return it }

        dir.mkdirs()
        File(dir, "res/values").mkdirs()
        File(dir, "src").mkdirs()

        File(dir, "AndroidManifest.xml").writeText(manifest(id))
        File(dir, "res/values/strings.xml").writeText(strings(cleanName))
        File(dir, "src/MainActivity.kt").writeText(mainActivity(id))
        File(dir, META_FILE).writeText(
            JSONObject().put("name", cleanName).put("applicationId", id).toString()
        )

        return null
    }

    /**
     * A legal package name from whatever somebody called their app.
     *
     * `com.example` rather than a Warp-branded prefix: an id is effectively
     * permanent once an app is installed anywhere, and picking somebody's
     * identity for them is not Warp's to do.
     */
    fun derivePackage(name: String): String {
        val slug = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString("")
            // A package segment cannot start with a digit, and "3d" is a
            // perfectly ordinary thing to call an app.
            .let { if (it.firstOrNull()?.isDigit() == true) "app$it" else it }
            .ifBlank { "app" }

        return "com.example.$slug"
    }

    /** The reason this is not a usable package name, or null. */
    fun validatePackage(id: String): String? {
        val segments = id.split('.')
        if (segments.size < 2) return "a package needs at least one dot, like com.example.notes"
        segments.forEach { segment ->
            if (segment.isEmpty()) return "a package cannot have an empty part"
            if (!segment.first().isLetter() && segment.first() != '_') {
                return "'$segment' cannot start with '${segment.first()}'"
            }
            if (!segment.all { it.isLetterOrDigit() || it == '_' }) {
                return "'$segment' can only hold letters, digits and underscores"
            }
            if (segment in JAVA_KEYWORDS) return "'$segment' is a reserved word"
        }
        return null
    }

    // ── what gets written ────────────────────────────────────────────────

    private fun manifest(id: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$id">

            <application
                android:label="@string/app_name"
                android:allowBackup="false">

                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    private fun strings(name: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">${name.escapeForXml()}</string>
        </resources>
    """.trimIndent()

    /**
     * A screen that says something, using a string resource.
     *
     * The resource reference is not decoration: it forces the awkward half of
     * the toolchain to work — aapt2 emits `R` as Java, javac compiles it, and
     * only then can Kotlin see it. A starting project that skipped resources
     * would compile while the pipeline was half broken.
     *
     * Plain `Activity`, no AppCompat and no Compose, because there is no
     * dependency resolution on the phone yet. A starting point that cannot build
     * is not a starting point.
     */
    private fun mainActivity(id: String) = """
        package $id

        import android.app.Activity
        import android.graphics.Color
        import android.os.Bundle
        import android.view.Gravity
        import android.widget.TextView

        class MainActivity : Activity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                val label = TextView(this).apply {
                    text = getString(R.string.app_name)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#101014"))
                }
                setContentView(label)
            }
        }
    """.trimIndent()

    private fun String.escapeForXml() = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;")

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
    )
}
