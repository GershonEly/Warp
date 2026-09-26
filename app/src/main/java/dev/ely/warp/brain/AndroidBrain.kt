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
        Read android_docs("compose") before writing your first screen, and
        android_docs("design") before choosing any colour or size.
        Sizes are always .dp — 16.dp, never a bare 16.

        Material 3's defaults are restrained on purpose, for dense screens.
        Taking them is a decision and usually the wrong one: choose a palette
        from what the person asked for, make one action obviously the main one,
        and never use dynamicColorScheme — it takes colours from the wallpaper,
        so the app is not what anyone chose.

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

    /**
     * The version of a page that suits this project, or null for pages that
     * have only one version.
     *
     * Only `design` is two pages behind one name so far. It is one name rather
     * than two because the model should not have to know which page it needs
     * before it knows what it needs — and a name it can guess wrong is a dead
     * end it cannot see coming.
     *
     * @param compose null before a project exists. A design page is no use at
     *   that point anyway, so the XML one is returned unchanged rather than
     *   inventing a third.
     */
    fun pageFor(topic: String, compose: Boolean?): String? = when {
        topic != "design" -> null
        compose == true -> DESIGN_COMPOSE
        else -> null
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

        ## 3D is available — OpenGL ES is in the framework

        `GLSurfaceView`, `GLES20` and `GLES30` compile here with nothing extra;
        they are part of Android, not a library. A `GLSurfaceView` can sit
        inside Compose through `AndroidView`, which is the ordinary way to put
        a game canvas in a Compose app:

        ```kotlin
        AndroidView(factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                setRenderer(MyRenderer())
            }
        })
        ```

        **What does not exist is a 3D *engine*** — no Unity, no Unreal, no scene
        graph, no physics library. Meshes are built from float arrays in code,
        and the matrix maths comes from `android.opengl.Matrix`.

        **And nothing can be downloaded.** There is no tool that fetches a
        binary, so models, textures, audio files and fonts cannot be brought in
        from the internet — they have to be generated in code. Say that once if
        it matters and build what you can; do not say *"there is no 3D"*, which
        is not true.

        ## The two errors that cost a build every time

        Both of these were hit by the first real app built here, in one
        compile. Neither is obvious and both are cheap to avoid.

        **1. Half of Material 3 is still "experimental".** `TopAppBar`,
        `Scaffold`'s bar slots, `SearchBar`, `ModalBottomSheet` and the pull-to
        -refresh components all fail to compile without an opt-in:

        ```kotlin
        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        fun HomeScreen() { TopAppBar(title = { Text("Sprout") }) }
        ```
        ```kotlin
        import androidx.compose.material3.ExperimentalMaterial3Api
        ```

        The error reads *"this material API is experimental and is likely to
        change"*. It is not a warning — the build stops.

        **2. A list in state is a `List`, not a `MutableList`.**

        ```kotlin
        // Right: hold an immutable list, replace it to change it
        var plants by remember { mutableStateOf(listOf<Plant>()) }
        plants = plants + Plant("Fern")
        plants = plants.filter { it.id != target.id }
        ```

        The error is *"actual type is 'List<Plant>', but 'MutableList<Plant>'
        was expected"*, and it appears on every line that changes the list at
        once, which makes it look worse than it is.

        There is a second way, for when you mutate often and never replace:

        ```kotlin
        val plants = remember { mutableStateListOf<Plant>() }
        plants.add(Plant("Fern"))       // no `by`, no reassignment
        ```

        **Pick one and keep it.** Mixing them — declaring
        `mutableStateListOf` and then assigning a new list to it — is exactly
        what produces that error.

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

        ## What an XML project has, and has not

        **This page is about projects built with XML layouts.** If you have not
        created the project yet, you can choose Compose instead — it compiles
        here and is the default. Read android_docs("compose") for that.

        **You have: XML layouts, drawables, themes, styles, colours, and the
        whole `android.widget` set.** `aapt2` compiles the entire `res/` folder
        and generates `R`, so every one of these already works.

        **You do not have, in an XML project: AppCompat, Material Components,
        or any other library.** There is no dependency resolution on the phone —
        only the Android framework and what you write. Compose is not used in
        *this* kind of project; that is a property of the project, not of the
        toolchain, and it was fixed once by choosing XML at creation.

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

    /**
     * Making a Compose app look considered — the page that was missing.
     *
     * A Compose project used to be handed [DESIGN], which opens by saying
     * `MaterialTheme` is not available. It is the most important thing in a
     * Compose app. The model believed it, took Material 3's defaults, and
     * produced something correct and characterless beside an XML app that had
     * been forced to decide everything for itself.
     *
     * Material 3's defaults are restrained on purpose — they are built for
     * dense, information-heavy screens. **Accepting them is a decision, and
     * usually the wrong one**, which is the single thing this page exists to
     * say. The rest is the same as the XML page, because the principle does not
     * change with the syntax: the colours belong to the person who asked.
     */
    private val DESIGN_COMPOSE = """
        # Making it look like something, in Compose

        Material 3 gives you working components immediately. That is the trap:
        **its defaults are restrained by design**, built for dense screens full
        of information. Accept them and you get an app that is correct, quiet
        and anonymous — every screen the same weight, nothing looking like it
        matters more than anything else.

        ## The colours are the person's, not yours and not this page's

        **This page will not give you a palette, deliberately.** A fixed example
        would be copied into every app built here, and they would all look the
        same — the same fault as defaulting to purple, only harder to spot.

        Work it out in this order:

        1. **What did they say?** *"like a gym app"*, *"make it green"*,
           *"dark"* — that is the answer. A colour they named is the primary.
        2. **What is the app?** If they said nothing about looks, pick from what
           it *is*. A notes app is not a racing game.
        3. **Say what you picked, in one line**, so they can change it with one
           sentence instead of describing a palette unprompted.

        **Do not use `dynamicColorScheme`.** It takes the colours from the
        phone's wallpaper, which means the app is not what anyone chose and
        looks different on every device. Write the scheme yourself:

        ```kotlin
        private val Dark = darkColorScheme(
            primary = Color(0x...),        // the one thing you want tapped
            onPrimary = Color(0x...),      // text on top of it
            surface = Color(0x...),        // cards
            onSurface = Color(0x...),      // text on cards
            background = Color(0x...),
            error = Color(0x...),          // destructive actions only
        )

        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) { }
        ```

        Both schemes, and `isSystemInDarkTheme()` between them. A bare
        `MaterialTheme { }` is **light only** and ignores dark mode entirely.

        After that, never write `Color(0x...)` again — use
        `MaterialTheme.colorScheme.primary` and friends, so changing the palette
        is one edit.

        ## Make one thing obviously the main action

        The most common failure here: every button is a `TextButton`, so nothing
        looks important and the primary action is invisible.

        | Weight | Use | For |
        |---|---|---|
        | Loudest | `Button` | the one thing you want tapped |
        | Middle | `FilledTonalButton`, `OutlinedButton` | secondary |
        | Quiet | `TextButton` | Cancel, Dismiss |
        | Floating | `FloatingActionButton` | the single add/create action |

        If a screen's whole point is *start the workout*, that is a `Button`
        across the width, not a line of text. **Tapping a card with no label is
        not a button** — if it does something, say so.

        Destructive actions use `MaterialTheme.colorScheme.error`, so Delete
        never looks like Save.

        ## Size for where it is used

        Material's defaults assume a phone held close, in your hand, with your
        attention. If the person told you otherwise — *"readable at arm's
        length"*, *"while I am driving"*, *"my grandmother uses it"* — that is an
        instruction about size, and defaults will not honour it.

        ```kotlin
        Text("12", style = MaterialTheme.typography.displayLarge)   // a countdown
        Button(modifier = Modifier.fillMaxWidth().height(64.dp)) { }
        ```

        ## Fill the screen or explain the space

        A `Column` with three items leaves the rest blank, and blank reads as
        unfinished. Either put something there — a list that grows, a big
        current value, an empty state that says what to do — or centre what you
        have so the space looks intended.

        ```kotlin
        if (items.isEmpty()) {
            Text("No workouts yet. Tap + to make one.")   // not a blank screen
        }
        ```

        ## Spacing is a decision too

        `Arrangement.spacedBy(12.dp)` once on a Column beats `padding` scattered
        over every child, and it stays consistent when the list changes.
        Group what belongs together and separate what does not — a screen where
        everything is 8.dp apart has no structure.
    """.trimIndent()

    private val DESIGN = """
        # Making it look like something, with what is here

        **This page is for a project built with XML layouts.** In one of those,
        Material Components is not available — no `MaterialButton`, no
        `MaterialTheme` — so what follows is how to get the same result from a
        theme, a colour file and some drawables, which you do have. A Compose
        project has Material 3 and gets a different page under this same name.

        ## The colours are the person's, not yours and not this page's

        **This page will not give you a palette, deliberately.** An earlier
        version printed five hex codes as an example and every app built here
        copied them exactly — so every app looked the same, which is the same
        fault as defaulting to purple, only less obvious.

        Work it out in this order:

        1. **What did they say?** *"like a gym app"*, *"make it green"*, *"dark"*,
           *"like Instagram"* — that is the answer, use it. If they named a
           colour, that colour is the accent.
        2. **What is the app?** If they said nothing about looks, pick from what
           it *is*. A notes app is not a racing game; a timer you stare at while
           exercising is not a bank statement.
        3. **Say what you picked, in one line.** *"Deep green with a warm
           accent, since it is a plant app."* Then they can change it with one
           sentence instead of having to describe a whole palette unprompted.

        Never ask them to choose a palette from nothing — that is a question
        almost nobody can answer. Pick, say, and let them correct you.

        **Do not default to purple on black.** It is Material's sample palette
        and the look of an app nobody chose the colours for.

        **Write the palette down** in `res/values/colors.xml` and never put a
        hex code anywhere else. Two neutrals, one accent and one colour for
        danger is enough for almost anything:

        ```xml
        <resources>
            <color name="bg">...</color>
            <color name="surface">...</color>
            <color name="text">...</color>
            <color name="muted">...</color>
            <color name="accent">...</color>
        </resources>
        ```

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
