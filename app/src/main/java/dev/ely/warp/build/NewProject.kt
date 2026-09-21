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
    data class Meta(
        val name: String,
        val applicationId: String,
        /**
         * The app's colour, taken from its icon.
         *
         * Stored rather than recomputed, because reading and averaging a PNG to
         * draw one tile is work the shelf would repeat for every app, every time
         * it is shown.
         */
        val colour: Int,
        /**
         * The key to this app's own door — §5m.
         *
         * Written into the generated provider at creation and held here, so Warp
         * can present it when it asks the app what it stored. Null for every
         * project made before the door existed, which is exactly the case the
         * tool has to report rather than read as "the app stored nothing".
         *
         * Not cryptography. A signature check is the usual answer and cannot be
         * used — Warp signs generated apps with a debug key that is not the key
         * Warp itself is signed with, as `CrashInbox` already records. This stops
         * the ambient case, which is the case that exists.
         */
        val dataKey: String? = null,
        /**
         * Built with Jetpack Compose rather than XML layouts — §8 item 11.
         *
         * **Decided when the project is made, and then it stays decided.** The
         * two are different ways to write a whole app, not a rendering option:
         * switching an existing project would leave every screen it already has
         * written in the other one. It is recorded here rather than guessed
         * from the sources, because "does this look like Compose" is exactly
         * the kind of inference that is right until it quietly is not.
         *
         * False for every project made before this existed, which is correct —
         * they are all XML.
         */
        val compose: Boolean = false,
    )

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
        return Meta(
            name = json.optString("name").ifBlank { id.substringAfterLast('.') },
            applicationId = id,
            // Older projects predate icons, so they fall back to the same seed
            // the shelf used before rather than to a colour that means nothing.
            colour = json.optInt("colour", 0).takeIf { it != 0 } ?: seedColour(id),
            dataKey = json.optString("dataKey").takeIf { it.isNotBlank() },
            compose = json.optBoolean("compose", false),
        )
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
     * The app's colour, now that it has a real icon — §8.
     *
     * [seedColour] is a hash, and §9h is blunt about what that is worth:
     * *decoration pretending to be meaning*. A generated icon replaces it with
     * a colour that is actually the app's, taken from its own artwork, and the
     * shelf and the drawer pick it up without knowing anything changed.
     *
     * Rewrites one field and keeps the rest, like [recordBuild] — the file also
     * holds the data key, and losing that would orphan everything the app has
     * saved.
     */
    fun recordColour(dir: File, colour: Int) {
        val json = runCatching { JSONObject(File(dir, META_FILE).readText()) }
            .getOrDefault(JSONObject())
        File(dir, META_FILE).writeText(json.put("colour", colour).toString())
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
     * A source file newer than the built APK, or null when the APK is current.
     *
     * **This is the check that was missing**, and an icon is what made it
     * visible. A drawn icon lands in `res/` and changes nothing you can see
     * until the app is compiled again — so installing the old APK, which is the
     * obvious thing to try, gives back the old icon and looks exactly like the
     * drawing having failed. It cost an afternoon and about four cents to work
     * that out, and the same silence applies to every edit ever made: change a
     * file, reinstall, get the previous app, with nothing anywhere saying so.
     *
     * Three exclusions, each for its own reason:
     * - `app.apk` is the output, and is trivially newer than itself.
     * - `warp.json` is written **after** the APK by [recordBuild], so it is
     *   newer than the build every single time.
     * - `.git` is git's own bookkeeping, which changes when you commit rather
     *   than when you edit — a commit would otherwise read as unbuilt work.
     *
     * @return the newest offending file, so the message can name it. Null when
     *   there is no APK at all: "nothing built yet" is a different answer with
     *   a different fix, and the caller already says it.
     */
    fun newerThanApk(dir: File): File? {
        val apk = lastApk(dir) ?: return null
        return dir.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" }
            .filter { it.isFile && it.name != "app.apk" && it.name != META_FILE }
            .filter { it.lastModified() > apk.lastModified() }
            .maxByOrNull { it.lastModified() }
    }

    /**
     * Why this APK must not be installed, or null when it is fine to install.
     *
     * Written once and used by both the tool and the Apps screen's button. Two
     * copies of this sentence would be two chances to fix the silence in one
     * place and leave it in the other — which is exactly how it survived this
     * long, since the chat and the button were already two paths to the same
     * stale file.
     */
    fun staleReason(dir: File): String? = newerThanApk(dir)?.let {
        "the built app is older than ${it.relativeTo(dir).path} — " +
            "installing it would put the previous version back. Build it again first."
    }

    /**
     * Create a project, or explain why not.
     *
     * @param applicationId blank to derive one from [name].
     * @return the failure reason, or null when it worked.
     */
    fun create(
        dir: File,
        name: String,
        applicationId: String = "",
        /** Write a Compose app instead of an XML one — §8 item 11. */
        compose: Boolean = false,
    ): String? {
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

        if (compose) {
            // No layout, no drawable, no colours file. A Compose app keeps all
            // three in Kotlin, and leaving empty folders behind would be an
            // invitation to fill them with XML that nothing reads.
            File(dir, "res/values/styles.xml").writeText(composeStyles())
            File(dir, "src/MainActivity.kt").writeText(composeMainActivity(id, cleanName))
        } else {
            File(dir, "res/layout").mkdirs()
            File(dir, "res/drawable").mkdirs()
            // The pattern to copy, not just a screen that runs — §5o.
            File(dir, "res/layout/activity_main.xml").writeText(activityLayout())
            File(dir, "res/values/colors.xml").writeText(colours())
            File(dir, "res/values/styles.xml").writeText(styles())
            File(dir, "res/drawable/card.xml").writeText(cardDrawable())
            File(dir, "src/MainActivity.kt").writeText(mainActivity(id))
        }
        File(dir, "src/CrashReporter.kt").writeText(crashReporter(id))
        // The app's own door — §5m. Written at creation with its key, because a
        // key added later would only reach apps rebuilt after it, and Warp would
        // have no way to tell those apart from apps that stored nothing.
        val dataKey = java.util.UUID.randomUUID().toString()
        File(dir, "src/WarpData.kt").writeText(warpData(id, dataKey))
        // §9h asks for the icon to exist from the start rather than after
        // the first successful build, so a project has a face — and therefore
        // a colour — immediately. It is a placeholder and is meant to be
        // replaced, but a placeholder that is a real file is worth far more than
        // one that only exists inside Warp.
        val colour = seedColour(id)
        runCatching { Icons.write(dir, Icons.drawDefault(cleanName, colour), colour) }

        File(dir, META_FILE).writeText(
            JSONObject()
                .put("name", cleanName)
                .put("applicationId", id)
                .put("colour", colour)
                .put("dataKey", dataKey)
                .put("compose", compose)
                .toString()
        )

        return null
    }

    /**
     * A starting colour, from the application id.
     *
     * A stand-in until an icon is generated or chosen, and only a stand-in: it
     * is derived from a hash, which §9h rejects as *decoration pretending to
     * be meaning*. The difference now is that it goes into a real icon file, and
     * the colour the app uses everywhere is read back **from that file** — so
     * the day a real icon arrives, nothing downstream changes.
     */
    fun seedColour(applicationId: String): Int {
        val hue = ((applicationId.hashCode() % 360) + 360) % 360
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.55f))
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
                android:name=".CrashReporter"
                android:label="@string/app_name"
                android:theme="@style/AppTheme"
                android:icon="@mipmap/ic_launcher"
                android:roundIcon="@mipmap/ic_launcher_round"
                android:allowBackup="false">

                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>

                <!--
                  The door Warp asks through - §5m. Exported, because a provider
                  only its own app can reach is a provider with nothing to do,
                  and guarded by a key inside it rather than by a signature:
                  Warp signs generated apps with a debug key that is not the key
                  Warp itself is signed with.
                -->
                <provider
                    android:name=".WarpData"
                    android:authorities="$id.warpdata"
                    android:exported="true" />
            </application>
        </manifest>
    """.trimIndent()

    /**
     * The app's own door, written into it at creation — §5m.
     *
     * Warp cannot read another app's sandbox and no permission changes that, so
     * the app serves its own files instead. Android starts a process to answer
     * its own provider, which is why this works while the app is closed — the
     * advantage the screenshot tool never had.
     *
     * Plain `ContentProvider` and `MatrixCursor`, no dependencies: the phone has
     * no dependency resolution, so anything a generated project uses has to be
     * in the platform already.
     *
     * **Reads from a copy**, never the live file. A database being written while
     * it is read gives a torn page or a lock, and SQLite's journal means the
     * newest rows may not be in the main file at all — the same lesson already
     * written down about pulling `warp.db-wal` beside `warp.db`.
     */
    private fun warpData(id: String, key: String) = """
        package $id

        import android.content.ContentProvider
        import android.content.ContentValues
        import android.database.Cursor
        import android.database.MatrixCursor
        import android.database.sqlite.SQLiteDatabase
        import android.net.Uri
        import java.io.File

        /**
         * Answers Warp's questions about this app's own storage.
         *
         * Written by Warp when the project was created. Safe to delete if you do
         * not want it - the app runs without it, and Warp will say it has no way
         * to look rather than pretend the app stored nothing.
         */
        class WarpData : ContentProvider() {

            override fun onCreate() = true

            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): Cursor? {
                val context = context ?: return null
                // Wrong key is silence, not an error: an answer that says "wrong
                // key" tells whoever asked that there is a key to guess.
                if (uri.getQueryParameter("key") != KEY) return null

                val root = context.dataDir
                return when (uri.getQueryParameter("what")) {
                    "list" -> list(root)
                    "read" -> read(root, uri.getQueryParameter("path"))
                    "table" -> table(root, uri.getQueryParameter("db"), uri.getQueryParameter("table"))
                    else -> null
                }
            }

            private fun list(root: File): Cursor {
                val out = MatrixCursor(arrayOf("path", "size"))
                root.walkTopDown().maxDepth(6).forEach { file ->
                    if (file.isFile) {
                        val path = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                        // Its own code and the library it was built against are
                        // not "what the app stored", and they are most of the
                        // bytes here.
                        if (!path.startsWith("code_cache") && !path.startsWith("lib")) {
                            // The type is written out. A bare arrayOf of a String
                            // and a Long infers an intersection for the reified
                            // parameter, which the on-device compiler treats as
                            // an error rather than a warning.
                            out.addRow(arrayOf<Any?>(path, file.length()))
                        }
                    }
                }
                return out
            }

            private fun read(root: File, path: String?): Cursor? {
                if (path.isNullOrBlank()) return null
                val file = File(root, path).canonicalFile
                // Inside this app or nowhere. A path is a string and `..` is a
                // string that looks fine.
                if (!file.path.startsWith(root.canonicalFile.path)) return null
                if (!file.isFile) return null

                val text = runCatching { file.readText() }.getOrElse { return null }
                val out = MatrixCursor(arrayOf("text", "size", "capped"))
                val capped = text.length > CAP
                out.addRow(
                    arrayOf<Any?>(
                        if (capped) text.take(CAP) else text,
                        file.length(),
                        if (capped) 1 else 0,
                    )
                )
                return out
            }

            private fun table(root: File, db: String?, name: String?): Cursor? {
                if (db.isNullOrBlank() || name.isNullOrBlank()) return null
                if (!name.all { it.isLetterOrDigit() || it == '_' }) return null

                val live = File(root, db).canonicalFile
                if (!live.path.startsWith(root.canonicalFile.path) || !live.isFile) return null

                // The copy, for the reason in the class comment above.
                val copy = File.createTempFile("warp", ".db", root.resolve("cache").also { it.mkdirs() })
                return try {
                    live.copyTo(copy, overwrite = true)
                    val handle = SQLiteDatabase.openDatabase(
                        copy.path, null, SQLiteDatabase.OPEN_READONLY
                    )
                    val rows = handle.rawQuery("SELECT * FROM " + name + " LIMIT " + ROWS, null)
                    val out = MatrixCursor(rows.columnNames)
                    while (rows.moveToNext()) {
                        out.addRow((0 until rows.columnCount).map { rows.getString(it) })
                    }
                    rows.close()
                    handle.close()
                    out
                } catch (e: Exception) {
                    null
                } finally {
                    copy.delete()
                }
            }

            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(
                uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
            ) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

            private companion object {
                const val KEY = "$key"
                const val CAP = 20000
                const val ROWS = 200
            }
        }
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
        import android.os.Bundle
        import android.widget.TextView

        class MainActivity : Activity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                // The screen is res/layout/activity_main.xml. Sizes belong
                // there, in dp - a number written in Kotlin is a pixel, which
                // on this phone is about a third of what you meant.
                setContentView(R.layout.activity_main)

                findViewById<TextView>(R.id.title).text = getString(R.string.app_name)
            }
        }
    """.trimIndent()

    /**
     * The first screen, as a layout rather than as code — §5o.
     *
     * This file exists to be copied. A real build watched on 2026-09-13 wrote
     * **115 edits into one Kotlin file and not one thing under `res/`**, because
     * the starter showed a programmatic `TextView` and that is what a model
     * imitates. Everything that went wrong followed: sizes in pixels came out a
     * third too small, views added without a container stacked on top of each
     * other, and nothing had a rounded corner because nothing had a drawable.
     *
     * So the starting point demonstrates the whole pattern in miniature: a
     * layout with dp, a named palette, a theme, and one drawable.
     */
    private fun activityLayout() = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:gravity="center"
            android:padding="24dp">

            <TextView
                android:id="@+id/title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@drawable/card"
                android:paddingHorizontal="24dp"
                android:paddingVertical="16dp"
                android:textColor="@color/text"
                android:textSize="24sp" />

        </LinearLayout>
    """.trimIndent()

    /** The palette, named once so no hex code is ever written twice — §5o. */
    private fun colours() = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <color name="bg">#0E1116</color>
            <color name="surface">#171B22</color>
            <color name="text">#ECEFF4</color>
            <color name="muted">#9AA4B2</color>
            <color name="accent">#4C8DFF</color>
        </resources>
    """.trimIndent()

    /**
     * A theme, because without one an app inherits the platform's oldest look.
     *
     * That is most of what "the fonts look wrong" turned out to mean.
     */
    private fun styles() = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="AppTheme" parent="@android:style/Theme.Material.NoActionBar">
                <item name="android:windowBackground">@color/bg</item>
                <item name="android:textColor">@color/text</item>
                <item name="android:colorAccent">@color/accent</item>
            </style>
        </resources>
    """.trimIndent()

    /**
     * The one piece of XML a Compose app still needs.
     *
     * Android decides what the window looks like *before* any Kotlin runs — the
     * background colour behind the first frame, and whether there is an action
     * bar — and that decision is made from a theme in the manifest. Everything
     * after the first frame is Compose's.
     *
     * `NoActionBar` matters: the default theme draws a grey title bar above the
     * app, which a Compose app then draws its own top bar underneath.
     */
    private fun composeStyles() = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="AppTheme" parent="@android:style/Theme.Material.Light.NoActionBar" />
        </resources>
    """.trimIndent()

    /**
     * The Compose starter — the pattern to copy, not just a screen that runs.
     *
     * §5o is the reason this is a real screen with state in it rather than a
     * lone `Text("Hello")`. The template is the most-read example in any
     * project: whatever it does, the model does more of. So it shows the four
     * things every Compose screen needs — `remember` for state, `MaterialTheme`
     * for colour and type, a `Scaffold` for the frame, and `dp` for spacing.
     *
     * Notably **no sizes in raw numbers**. The Kotlin-number-is-a-pixel trap
     * that made every generated app tiny does not exist in Compose — `16.dp` is
     * the only way to write it — and the template models that from line one.
     */
    private fun composeMainActivity(applicationId: String, appName: String) = """
        package $applicationId

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.padding
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.material3.Button
        import androidx.compose.material3.Card
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Scaffold
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableIntStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp

        class MainActivity : ComponentActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContent {
                    // Wrap everything once, here. MaterialTheme is where the
                    // colours and text styles come from; without it every
                    // Text below falls back to an unstyled default.
                    MaterialTheme {
                        Scaffold { padding ->
                            HomeScreen(Modifier.padding(padding))
                        }
                    }
                }
            }
        }

        @Composable
        fun HomeScreen(modifier: Modifier = Modifier) {
            // State lives in the screen and survives redraws because of
            // `remember`. Change it and the parts that read it redraw
            // themselves — you never update a view by hand.
            var taps by remember { mutableIntStateOf(0) }

            Column(
                modifier = modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("$appName", style = MaterialTheme.typography.headlineMedium)

                Card(modifier = Modifier.padding(top = 24.dp)) {
                    Text(
                        "Tapped ${'$'}taps times",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Button(onClick = { taps++ }, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Tap me")
                }
            }
        }
    """.trimIndent()

    /** One drawable, so the first rounded corner is already there to copy. */
    private fun cardDrawable() = """
        <?xml version="1.0" encoding="utf-8"?>
        <shape xmlns:android="http://schemas.android.com/apk/res/android"
            android:shape="rectangle">
            <solid android:color="@color/surface" />
            <corners android:radius="16dp" />
        </shape>
    """.trimIndent()

    /**
     * Sends the crash to Warp before the app dies.
     *
     * An `Application` rather than something set up in `onCreate`, and that is
     * the whole point: the first crash this project ever produced happened while
     * Android was *instantiating* MainActivity, so a handler installed inside
     * the activity would never have run. An Application is built before any
     * activity exists, which is early enough to catch that.
     *
     * It writes through a ContentProvider rather than a file, because there is
     * no path on the phone that one app can write and another can read without a
     * permission somebody has to grant — and the log permission this
     * replaces is exactly the one that kept expiring.
     *
     * The default handler is still called afterwards, so the app dies the way it
     * would have. Swallowing the crash would leave a frozen app and a report of
     * a crash nobody saw.
     */
    private fun crashReporter(id: String) = """
        package $id

        import android.app.Application
        import android.content.ContentValues
        import android.net.Uri
        import java.io.PrintWriter
        import java.io.StringWriter

        class CrashReporter : Application() {
            override fun onCreate() {
                super.onCreate()
                val previous = Thread.getDefaultUncaughtExceptionHandler()

                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    try {
                        val trace = StringWriter()
                        error.printStackTrace(PrintWriter(trace))

                        contentResolver.insert(
                            Uri.parse("content://dev.ely.warp.crashes"),
                            ContentValues().apply {
                                put("package", packageName)
                                put(
                                    "trace",
                                    "Thread: " + thread.name + "\n" + trace.toString(),
                                )
                            },
                        )
                    } catch (ignored: Throwable) {
                        // Reporting must never be the reason a crash is lost.
                    }
                    previous?.uncaughtException(thread, error)
                }
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
