package dev.ely.warp.build

import java.io.File

/**
 * Writes a tiny but complete Android project that Warp can compile.
 *
 * This is the "hello world" that proves the whole pipeline end to end. It
 * deliberately references `R.string`, so the awkward part of the chain has to
 * work: aapt2 generates `R` as Java, javac compiles it, and only then can
 * Kotlin see it.
 *
 * It uses the plain framework `Activity` rather than AppCompat or Compose so
 * that no dependency resolution is involved — the point is to test the
 * toolchain, not a dependency graph.
 */
object SampleProject {

    const val APPLICATION_ID = "dev.ely.warpsample"

    /**
     * Create the project at [dir], replacing anything already there.
     *
     * @return the project directory, ready to hand to [BuildEngine]
     */
    fun write(dir: File): File {
        dir.deleteRecursively()
        dir.mkdirs()

        File(dir, "AndroidManifest.xml").writeText(MANIFEST)

        File(dir, "res/values").mkdirs()
        File(dir, "res/values/strings.xml").writeText(STRINGS)

        File(dir, "src").mkdirs()
        File(dir, "src/MainActivity.kt").writeText(MAIN_ACTIVITY)

        return dir
    }

    private val MANIFEST = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$APPLICATION_ID">

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

    private val STRINGS = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">Built by Warp</string>
        </resources>
    """.trimIndent()

    private val MAIN_ACTIVITY = """
        package $APPLICATION_ID

        import android.app.Activity
        import android.graphics.Color
        import android.os.Bundle
        import android.view.Gravity
        import android.widget.TextView

        class MainActivity : Activity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                val label = TextView(this).apply {
                    text = getString(R.string.app_name) +
                        "\n\nThis app was compiled on the phone."
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#101014"))
                }

                setContentView(label)
            }
        }
    """.trimIndent()
}
