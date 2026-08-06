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
        Topics: project · icons · compose · material · rules · gotchas
        Never ask the user for icon sizes, folder names, manifest boilerplate or
        SDK rules. Look them up instead. Do not guess a version number or an API
        you are unsure of — say what you are unsure about.
    """.trimIndent()

    /** Every topic, by name. The tool refuses anything not in here. */
    val topics: Map<String, String> by lazy {
        mapOf(
            "project" to PROJECT,
            "icons" to ICONS,
            "compose" to COMPOSE,
            "material" to MATERIAL,
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
          values/strings.xml
          values/ic_launcher_background.xml
          mipmap-anydpi-v26/ic_launcher.xml
          mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
        ```

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
        # Compose — the traps that actually bite

        ## State

        - `remember { mutableStateOf(x) }` survives recomposition.
        - `rememberSaveable` survives rotation and process death. Anything a
          person typed belongs here; a scroll position usually does not.
        - `by` unwraps it: `var n by remember { mutableStateOf(0) }` then read
          `n`, not `n.value`.
        - **Hoist state** to the lowest common caller. A composable that owns
          state it did not create cannot be previewed or reused.

        ## Recomposition

        - Reading a state value inside a lambda that runs later (`onClick`) does
          not subscribe that composable to it. Reading it in the body does.
        - An unstable parameter — a plain `List`, a lambda recreated every call
          — makes a composable recompose every time its parent does. `List` is
          not stable; `ImmutableList` or a `data class` holding one is.
        - Never do work in a composable body. It runs an unknown number of
          times. Use `LaunchedEffect(key)` for anything that should happen once.

        ## Lists

        - `LazyColumn`, and give `items(list, key = { it.id })` a key. Without
          one, deleting a row animates the wrong item and state jumps between
          rows.
        - Nesting a scrollable in a scrollable of the same direction throws at
          runtime, not at compile time.

        ## Side effects

        - `LaunchedEffect(Unit)` runs once per composition entry.
        - `DisposableEffect` when something must be undone.
        - `rememberCoroutineScope()` dies with the screen — anything that must
          outlive it belongs on a longer-lived scope. Warp learned this when
          leaving the app cancelled a running turn.
    """.trimIndent()

    private val MATERIAL = """
        # Material 3

        ## Colour roles

        Use roles, never raw colours: `primary` / `onPrimary`,
        `primaryContainer` / `onPrimaryContainer`, `surface`, `surfaceVariant`,
        `surfaceContainer{,High,Highest}`, `outline`, `outlineVariant`, `error`.
        The `on-` colour is what is legible on top of its pair — picking your own
        is how contrast breaks in the other theme.

        ## Type

        `displayLarge` → `headlineMedium` → `titleMedium` → `bodyLarge` →
        `labelMedium`. Body text is `bodyLarge`; `labelMedium` is for chips and
        captions.

        ## Layout

        - **48 dp** is the smallest touchable target. A 24 dp icon needs padding
          around it, not a bigger icon.
        - Spacing on a 4 dp grid. Pick a scale and keep to it.
        - Dark themes tint their neutrals — pure grey reads as cheap.

        ## Dynamic colour

        `dynamicDarkColorScheme(context)` on Android 12+, with a static fallback
        below it. It is a nice default and a poor brand: an app that must look
        like itself should not use it.
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
