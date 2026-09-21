package dev.ely.warp.brain

/**
 * What Warp already knows about Android — §7.
 *
 * The promise is *"you never explain any of that"*: not the icon densities, not
 * where the manifest wants things, not which Compose call survives a rotation.
 * A model that has to be told those every time is a model you are teaching
 * instead of using.
 *
 * **It is a tool, not a prompt, and that is the whole design.** The obvious
 * version — paste everything into the system prompt — is the most expensive
 * thing this app could do: every line would be re-sent with every message, for
 * the rest of the conversation, on the account holder's own key. That is
 * precisely the waste `7c93b98` removed from tool output, and building it back
 * in deliberately would be worse than never having noticed.
 *
 * So what is always present is [SUMMARY], fifteen lines naming what can be
 * asked for. Everything else costs nothing until the moment it is needed, and
 * then costs once.
 *
 * Written as prose rather than links because there is no network — §5g. This is
 * what Warp knows on a phone with the aeroplane mode on.
 */
object AndroidBrain {

    /**
     * Always in context. Deliberately tiny.
     *
     * It names the topics rather than teaching any of them, because the only
     * job it has is to stop the model inventing an answer when a correct one is
     * one tool call away. Every line here is paid for on every message, so
     * every line has to earn it.
     */
    val SUMMARY = """
        Android knowledge you already have, without asking the user:
        call android_docs(topic) to read any of these in full, once, when you need it.
        Topics: project · layout · design · icons · rules · gotchas

        This toolchain has NO Compose and NO libraries — only the Android
        framework. Screens are XML layouts in res/layout, styled with colours,
        themes and drawables. Read android_docs("layout") before writing UI.
        A number in Kotlin is a PIXEL, so 16 is about 6 dp: put sizes in XML.

        Never ask the user for icon sizes, folder names, manifest boilerplate or
        SDK rules. Look them up instead. Do not guess a version number or an API
        you are unsure of — say what you are unsure about.
    """.trimIndent()

    /**
     * The same summary, for a project that is built with Compose.
     *
     * **Two summaries rather than one hedged one.** §5o's finding was that a
     * model told about a toolchain it does not have will use it anyway: the
     * Brain taught Compose, nothing could compile Compose, and 115 edits went
     * into one Kotlin file while `res/` stayed empty. Telling a model *"you
     * have Compose, unless this project doesn't, in which case XML"* invites
     * exactly the same failure from the other direction.
     *
     * So the model is told what this project is, flatly, and never the other.
     */
    val SUMMARY_COMPOSE = """
        Android knowledge you already have, without asking the user:
        call android_docs(topic) to read any of these in full, once, when you need it.
        Topics: project · compose · design · icons · rules · gotchas

        THIS PROJECT USES JETPACK COMPOSE. Build every screen in Kotlin with
        @Composable functions. There is no layout XML, no findViewById and no
        setContentView — MainActivity calls setContent { }. Material 3 is
        available: MaterialTheme, Scaffold, Card, Button, Text.
        Read android_docs("compose") before writing your first screen.
        Sizes are always .dp — 16.dp, never a bare 16.

        Available: the Android framework, Compose, Material 3, coroutines.
        Nothing else — no image loaders, no networking libraries, no Room.

        Never ask the user for icon sizes, folder names, manifest boilerplate or
        SDK rules. Look them up instead. Do not guess a version number or an API
        you are unsure of — say what you are unsure about.
    """.trimIndent()

    /**
     * What to say before there is a project at all.
     *
     * **The state that made the first version of this a self-fulfilling lie.**
     * A new conversation has no project, so asking it "is this a Compose
     * project?" answered *no* — and the model was handed the summary that says
     * *"this toolchain has NO Compose"*. It believed that, told the person
     * Compose would not compile, and created an XML project. Which made the
     * statement true. The person had asked for Compose in their first sentence.
     *
     * So the empty state gets its own answer rather than being folded into the
     * XML one. Nothing is being written yet; the only decision in front of the
     * model is which kind of project to make, and it needs to know both exist.
     */
    val SUMMARY_NEW = """
        Android knowledge you already have, without asking the user:
        call android_docs(topic) to read any of these in full, once, when you need it.
        Topics: project · compose · layout · design · icons · rules · gotchas

        There is no project in this conversation yet. When you create one with
        new_project you choose how it is built, and that cannot be changed later:

        - Jetpack Compose — the default. Screens are @Composable functions in
          Kotlin, Material 3 and dark mode come free. Adds about 8 MB.
        - XML layouts — res/layout and findViewById. About 50 KB, looks plainer.

        Compose IS available here and does compile. Use it unless the person
        asked for XML or said the app must be as small as possible. Do not ask
        them to choose between two Android toolkits; pick Compose and say so in
        one line.

        Never ask the user for icon sizes, folder names, manifest boilerplate or
        SDK rules. Look them up instead. Do not guess a version number or an API
        you are unsure of — say what you are unsure about.
    """.trimIndent()

    /**
     * Whichever of the three fits.
     *
     * @param compose null when there is no project yet — a real state with its
     *   own answer, not a reason to fall back to "no Compose".
     */
    fun summaryFor(compose: Boolean?): String = when (compose) {
        null -> SUMMARY_NEW
        true -> SUMMARY_COMPOSE
        false -> SUMMARY
    }

    /** Every topic, by name. The tool refuses anything not in here. */
    val topics: Map<String, String> by lazy {
        mapOf(
            "project" to PROJECT,
            // Before icons, because it is the one that decides whether an app
            // looks like anything — §5o.
            "layout" to LAYOUT,
            // Its Compose counterpart. Both are always readable — a topic the
            // tool refuses depending on the project would be a dead end the
            // model cannot see coming — but only one is ever named in the
            // summary, so only one gets read.
            "compose" to COMPOSE,
            "design" to DESIGN,
            "icons" to ICONS,
            "rules" to RULES,
            "gotchas" to GOTCHAS,
        )
    }

    val names: List<String> get() = topics.keys.toList()

    private val PROJECT = """
        # Project layout

        A Warp project is a flat, Gradle-free source tree. The build engine
        compiles it directly, so there is no `app/` module and no `build.gradle`.

        ```
        AndroidManifest.xml
        warp.json                  name, applicationId
        src/                       .kt files, all compiled together
        res/
          layout/activity_main.xml   screens - see the `layout` topic
          drawable/card.xml          shapes, corners, gradients
          values/strings.xml
          values/colors.xml          the palette, named once
          values/styles.xml          the theme
          values-night/colors.xml    the same names, dark values
          values/ic_launcher_background.xml
          mipmap-anydpi-v26/ic_launcher.xml
          mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
        ```

        **Everything under `res/` is compiled** — `aapt2` is given the whole
        folder and `R` is generated from it. Layouts, drawables, themes and
        colours all work. Use them: they are the difference between an app that
        looks made and one assembled out of coloured rectangles in Kotlin.

        ## The manifest a build needs

        - `package` is set from `warp.json`, not written by hand.
        - One `<activity>` with `android:exported="true"` and the LAUNCHER
          intent filter. Missing `exported` is a hard install failure on
          Android 12+, not a warning.
        - `android:icon="@mipmap/ic_launcher"` — without it the app installs
          blank even when every PNG is present. The files being in the APK is
          not the same as anything pointing at them.

        ## Strings

        Anything shown to a person goes in `res/values/strings.xml`, and `&`
        must be written `&amp;` — an unescaped ampersand in an app name is an
        aapt2 failure with a line number nobody reads.
    """.trimIndent()

    private val ICONS = """
        # Icons — every size Android wants

        ## Classic launcher PNG

        | Folder | Size |
        |---|---|
        | `mipmap-mdpi/` | 48 × 48 |
        | `mipmap-hdpi/` | 72 × 72 |
        | `mipmap-xhdpi/` | 96 × 96 |
        | `mipmap-xxhdpi/` | 144 × 144 |
        | `mipmap-xxxhdpi/` | 192 × 192 |

        ## Adaptive icon — required on Android 8+

        - Canvas is **108 × 108 dp** (432 × 432 px at xxxhdpi).
        - Everything that must be visible stays inside the middle **72 × 72 dp**.
        - The outer 18 dp on each side is masked or used for parallax. Art that
          reaches the edge gets cropped into a circle on most launchers.
        - `mipmap-anydpi-v26/ic_launcher.xml` declares the background and
          foreground layers; the background is usually a colour resource, not a
          PNG, because five identical coloured squares is five files that a
          single `<color>` does better.

        ## In Warp

        `Icons.write(project, artwork, colour)` produces all of it — every
        density, the adaptive pair, and the manifest line. Never write icon
        files by hand, and never ask the user for a size.
    """.trimIndent()

    private val COMPOSE = """
        # Building a screen with Compose — read this before writing any UI

        ## What this project has, and has not

        **You have: Jetpack Compose, Material 3, coroutines, and the whole
        Android framework.** The Compose compiler and every library it needs
        ship with the toolchain, so `@Composable` compiles here.

        **You do not have: any other library.** No image loader, no networking
        library, no Room, no navigation library. There is no dependency
        resolution on the phone. If a task really needs one, say so plainly,
        once, and build what you can without it.

        **There is no layout XML in this project.** Do not create `res/layout/`,
        do not call `setContentView`, and do not use `findViewById`. They will
        not fail loudly — they will simply never draw anything, which is worse.

        ## The shape of every screen

        ```kotlin
        class MainActivity : ComponentActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContent {
                    MaterialTheme {          // colours and type live here
                        Scaffold { padding ->
                            HomeScreen(Modifier.padding(padding))
                        }
                    }
                }
            }
        }
        ```

        `MaterialTheme` is not optional decoration. Without it every `Text`
        falls back to an unstyled default and the app looks broken.

        ## State: you never update a view

        ```kotlin
        var count by remember { mutableIntStateOf(0) }
        Button(onClick = { count++ }) { Text("Tapped ${'$'}count") }
        ```

        Change the state; the parts that read it redraw themselves. There is no
        `textView.setText(...)`. State that must survive rotation uses
        `rememberSaveable` instead of `remember`.

        Use `mutableIntStateOf` for Int, `mutableStateOf` for everything else.

        ## Sizes are always dp

        ```kotlin
        Modifier.padding(16.dp)     // correct
        Modifier.padding(16)        // does not compile
        ```

        The pixel trap that ruins XML apps cannot happen here: `.dp` is the only
        thing that type-checks. Use `.sp` for text sizes.

        ## What to reach for

        | Want | Use |
        |---|---|
        | vertical stack | `Column` |
        | horizontal row | `Row` |
        | overlap | `Box` |
        | long list | `LazyColumn` — **not** `Column` with a scroll |
        | a panel | `Card` |
        | screen frame, top bar | `Scaffold`, `TopAppBar` |
        | spacing between children | `Arrangement.spacedBy(8.dp)` |

        `LazyColumn` matters: a `Column` builds every child at once, so a list
        of a thousand rows builds a thousand rows.

        ## Colour and type come from the theme

        ```kotlin
        Text("Score", style = MaterialTheme.typography.titleLarge)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) { }
        ```

        Do not hard-code `Color(0xFF6650a4)` everywhere. Material 3 gives a
        complete palette that works in dark mode for free; a hand-picked colour
        does not, and black-on-black is how a generated app ends up unreadable.

        ## Imports are not automatic

        Every composable needs its own import. The common ones:

        ```kotlin
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Text
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp
        ```

        `getValue` and `setValue` are what make `by remember` work. Leaving them
        out is the most common compile error in a Compose file.
    """.trimIndent()

    private val LAYOUT = """
        # Building a screen here — read this before writing any UI

        ## What this toolchain has, and has not

        **You have: XML layouts, drawables, themes, styles, colours, and the
        whole `android.widget` set.** `aapt2` compiles the entire `res/` folder
        and generates `R`, so every one of these already works.

        **You do not have: Compose, AppCompat, Material Components, or any
        other library.** There is no dependency resolution on the phone — only
        the Android framework and what you write. Compose will not compile. If a
        task really needs it, say so plainly and build what you can without it.

        ## The mistake that ruins apps built here

        **A number in Kotlin is a PIXEL. A number in XML is what you write.**

        ```kotlin
        view.setPadding(16, 16, 16, 16)   // 16 PIXELS - about 6 dp. Tiny.
        ```
        ```xml
        android:padding="16dp"            <!-- 16 dp. Correct everywhere. -->
        ```

        A phone is around 440 dpi, so a raw pixel number comes out roughly **a
        third of the size you meant**. This is the single most common reason an
        app built here looks wrong: everything is small and cramped.

        **So put the layout in XML.** When something must be sized in code,
        convert:

        ```kotlin
        val Int.dp: Int get() =
            (this * resources.displayMetrics.density).toInt()
        view.setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        ```

        `TextView.textSize = 22f` is the one exception — that setter is already
        in **sp**, which is what text should use.

        ## Views stack unless something arranges them

        Adding views without a container puts them **on top of each other**.
        Overlapping text is not a mystery, it is a missing layout.

        - `LinearLayout` — a row or a column. `layout_weight` shares the space.
        - `FrameLayout` — deliberately stacked, for overlays only.
        - `ScrollView` — one child, and that child is usually a `LinearLayout`.
        - `androidx.constraintlayout` is **not available** — nest simply instead.

        ```xml
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:padding="24dp">
            <TextView
                android:id="@+id/title"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="28sp" />
        </LinearLayout>
        ```
        ```kotlin
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.title).text = "Ready"
        ```

        ## Nothing is rounded by default

        A background colour is a rectangle. Rounded corners are a **drawable**,
        and drawables are cheap — `res/drawable/card.xml`:

        ```xml
        <shape xmlns:android="http://schemas.android.com/apk/res/android"
            android:shape="rectangle">
            <solid android:color="@color/surface" />
            <corners android:radius="16dp" />
        </shape>
        ```

        Then `android:background="@drawable/card"`. A `<gradient>` works the same
        way, and `<stroke>` gives an outline. An app made only of squares is an
        app with no drawables in it.

        ## A game or anything drawn

        Subclass `View`, override `onDraw(canvas)`, and keep a `Paint` as a field
        — allocating inside `onDraw` stutters. Sizes there are pixels too, so
        scale by `resources.displayMetrics.density` once and work in your own
        units. `invalidate()` asks for one more frame; for animation use
        `postInvalidateOnAnimation()`.

        Touch goes through `onTouchEvent`: `ACTION_DOWN` to start, `ACTION_MOVE`
        to drag, `ACTION_UP` to drop. Return `true` from `ACTION_DOWN` or you
        never see the rest.
    """.trimIndent()

    private val DESIGN = """
        # Making it look like something, with what is here

        Material Components is **not available** — no `MaterialButton`, no
        `MaterialTheme`. What follows is how to get the same result from a theme,
        a colour file and some drawables, which you do have.

        ## Do not default to purple on black

        Purple-on-black is Material's sample palette and it is what every model
        reaches for when it has not decided anything. It is the look of an app
        nobody chose the colours for.

        **Decide a palette and write it down** in `res/values/colors.xml`, then
        never write a hex code anywhere else:

        ```xml
        <resources>
            <color name="bg">#0E1116</color>
            <color name="surface">#171B22</color>
            <color name="text">#ECEFF4</color>
            <color name="muted">#9AA4B2</color>
            <color name="accent">#4C8DFF</color>
        </resources>
        ```

        Pick the accent from what the app *is* — a notes app is not a racing
        game. Two neutrals, one accent, and one colour for danger is enough for
        almost anything.

        ## A theme, or you get the 2011 default

        With no theme the app inherits the platform's oldest look, and that is
        most of "the fonts are wrong". `res/values/styles.xml`:

        ```xml
        <resources>
            <style name="AppTheme" parent="@android:style/Theme.Material.NoActionBar">
                <item name="android:windowBackground">@color/bg</item>
                <item name="android:textColor">@color/text</item>
                <item name="android:colorAccent">@color/accent</item>
            </style>
        </resources>
        ```

        Then `android:theme="@style/AppTheme"` on `<application>`. Add
        `res/values-night/colors.xml` with the same names and dark values and the
        whole app follows the phone — no code, no check.

        ## Sizes that read well on a phone

        These are **dp in XML**, and pixels if you write them in Kotlin — see the
        `layout` topic before using any of them in code.

        - **48 dp** is the smallest thing a thumb reliably hits. A 24 dp icon
          gets padding, not a bigger icon.
        - Screen edges **16–24 dp**. Space between unrelated blocks **16–24 dp**,
          inside a block **8 dp**.
        - Body text **16 sp**, titles **20–28 sp**, captions **12–14 sp**.
          Never below 12 sp.
        - Corner radius **12–16 dp** on cards, **8 dp** on small controls. Pick
          one and use it everywhere.

        ## Cheap things that make it look finished

        - A pressed state: `res/drawable/button.xml` as a `<selector>` with a
          different `<solid>` for `android:state_pressed="true"`. Without it,
          nothing on screen answers a tap.
        - `android:elevation="2dp"` on a card, once. Shadows everywhere is worse
          than none.
        - Line spacing: `android:lineSpacingMultiplier="1.2"` on anything longer
          than a line.
        - Alignment beats decoration. Things lining up on one edge is most of
          what "designed" looks like.
    """.trimIndent()

    private val RULES = """
        # Android rules that decide whether it runs

        ## Permissions

        - Anything "dangerous" is asked for at runtime, every time, and can be
          refused for ever. Declaring it in the manifest is not asking.
        - `RECORD_AUDIO`, `CAMERA`, location, contacts, calendar: runtime.
        - `INTERNET`: manifest only, never asked.
        - Signature-level permissions — `CAPTURE_AUDIO_OUTPUT` and friends — can
          only be held by apps signed with the platform key. No amount of
          tapping Allow grants one, and no sideloaded app can ever have one.

        ## Background

        - A process with no visible activity is killed whenever the system
          likes, and sooner on Xiaomi, Samsung and Huawei.
        - Work that must finish needs a **foreground service** with a
          notification, and the notification channel must exist before the
          permission prompt can appear.
        - `WorkManager` for anything deferrable.

        ## Resource qualifiers

        `-night`, `-land`, `-sw600dp`, `-v26`, `-hdpi`. They stack:
        `values-night-v31`. Most specific match wins.

        ## SDK levels

        - `minSdk` is the oldest phone it installs on.
        - `targetSdk` is the newest set of behaviour changes you have agreed to.
          It is not "the version it runs on" — every phone runs it.
        - `compileSdk` is which APIs you can name at all, and should be the
          newest.
    """.trimIndent()

    private val GOTCHAS = """
        # Things that fail quietly

        These cost real days on this project, which is why they are written down
        rather than rediscovered.

        - **A keyboard that breaks the layout**: the activity needs
          `windowSoftInputMode="adjustResize"` **and** insets handled in exactly
          one place. The keyboard inset already includes the navigation bar, so
          adding `imePadding()` as well doubles it.
        - **A variable font that renders at one weight**: Android caches a
          `Typeface` by resource id, so several `Font` entries on one `.ttf`
          collapse into one. One XML per weight, each with its own
          `fontVariationSettings`. Fails silently — the font still loads.
        - **A right-to-left phone mirroring an English layout**: if every label
          is English, mirroring makes it worse. Force LTR or translate properly.
        - **An icon that installs blank**: the PNGs are in the APK and nothing
          points at them. The manifest needs `android:icon`.
        - **`exported` missing on the launcher activity**: installs fine on old
          phones, hard-fails on Android 12+.
        - **A recording during a call that contains silence**: a non-privileged
          app gets a valid file with no audio. It is not a bug to fix.
        - **Reading a Room database from outside**: it runs in WAL mode, so the
          `-wal` file has to be copied with it or recent writes are invisible.
    """.trimIndent()
}
