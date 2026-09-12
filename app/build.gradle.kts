plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ ships Kotlin support built in — the standalone
    // org.jetbrains.kotlin.android plugin must NOT be applied.
    alias(libs.plugins.kotlin.compose)
    // Room's annotation processor. KSP rather than kapt: kapt runs the Java
    // compiler over stubs of every Kotlin file, and on a project this size that
    // is the difference between a build you wait for and one you don't.
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.ely.warp"

    // All modern APIs stay available to us…
    // (37 is required by current AndroidX; compileSdk is independent of both
    //  targetSdk and minSdk, so raising it changes nothing at runtime.)
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ely.warp"

        // Must not exceed targetSdk (28). Android 9 is also the oldest release
        // where our adaptive+monochrome icons and exec-from-data-dir both hold.
        minSdk = 28

        // …but we TARGET 28 on purpose.
        //
        // Apps targeting API 29+ may not exec() files stored in their own
        // writable data directory. Warp's whole point is running a compiler
        // toolchain from that directory, so targeting 28 is load-bearing.
        // This is the same approach Termux uses.
        //
        // Install with:
        //   adb install --bypass-low-target-sdk-block app-debug.apk
        //noinspection ExpiredTargetSdkVersion,OldTargetApi
        targetSdk = 28

        versionCode = 1
        versionName = "0.1.0-step0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The toolchain asset is already a compressed zip. Storing it without
        // a second round of compression keeps the build fast and lets us
        // stream it straight out of the APK at install time.
        noCompress += "zip"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            // JGit and the Apache SSHD jars it brings each carry their own copy
            // of the same jar metadata, and two files at one path stops the
            // merge outright — §5n.
            //
            // Dropped, because none of it means anything inside an APK: OSGi
            // metadata describes an Eclipse plugin, DEPENDENCIES and INDEX.LIST
            // describe a jar, and a jar's own signature cannot survive being
            // repacked into one that is signed again.
            excludes += "/OSGI-INF/**"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.{SF,DSA,RSA}"

            // Kept, not dropped, and the difference matters. Apache 2.0 asks for
            // NOTICE to travel with what you ship, and Warp is open source — so
            // these take the first copy rather than being excluded, which would
            // quietly remove someone else's licence text from the app.
            pickFirsts += "/META-INF/LICENSE*"
            pickFirsts += "/META-INF/NOTICE*"
        }
        jniLibs {
            // Extract native libs to disk rather than running them from inside
            // the APK. Warp needs real files on disk it can exec — and this is
            // also the fallback path if `targetSdk 28` ever stops working.
            // (Replaces android:extractNativeLibs in the manifest, which
            //  current AGP rejects.)
            useLegacyPackaging = true
        }
        // Toolchain assets are unpacked at runtime; the explicit no-compress
        // list lands in Task 3 alongside the real toolchain.
    }

    lint {
        // targetSdk 28 is intentional, not an oversight — see defaultConfig above.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }

    kotlin {
        jvmToolchain(17)
    }
}

// ── Toolchain asset ─────────────────────────────────────────────────────────
//
// The 172 MB toolchain is not in the repo. It is built on a PC with
//     py toolchain/build_toolchain.py --sdk <sdk>
// and this task copies the result into assets so it ships inside the APK.
//
// If the bundle is missing the build still succeeds — the app just cannot
// install a toolchain until one is supplied. That keeps UI work fast: pass
//     -Pwarp.includeToolchain=false
// to skip the 172 MB copy and get a small, quick APK.
val includeToolchain = (project.findProperty("warp.includeToolchain") as String?)
    ?.toBooleanStrictOrNull() ?: true

val toolchainAsset = layout.projectDirectory.file("src/main/assets/toolchain.zip").asFile
val toolchainBuildDir = rootProject.layout.projectDirectory.dir("toolchain/build").asFile

val syncToolchainAsset = tasks.register("syncToolchainAsset") {
    group = "warp"
    description = "Copies the built toolchain bundle into assets so it ships in the APK"

    // Deliberately not declaring inputs/outputs: the bundle is an optional,
    // externally produced artifact, and a stale-but-present asset is fine.
    outputs.upToDateWhen { false }

    // Captured as plain values here, at configuration time. Referring to the
    // script's own properties from inside doLast would capture the script
    // object itself, which the configuration cache cannot serialize.
    val include = includeToolchain
    val assetFile = toolchainAsset
    val bundleDir = toolchainBuildDir

    doLast {
        if (!include) {
            if (assetFile.exists()) {
                assetFile.delete()
                println("warp: removed toolchain asset (includeToolchain=false)")
            }
            return@doLast
        }

        val newest = bundleDir
            .listFiles { f -> f.isFile && f.name.startsWith("warp-toolchain-arm64-") && f.extension == "zip" }
            ?.maxByOrNull { it.lastModified() }

        if (newest == null) {
            println(
                "warp: WARNING no toolchain bundle found in ${bundleDir.path}.\n" +
                    "      The APK will build WITHOUT a toolchain.\n" +
                    "      Build one with: py toolchain/build_toolchain.py --sdk <sdk>"
            )
            return@doLast
        }

        // Skip the copy when the asset already matches, so incremental builds
        // do not move 172 MB every time.
        if (assetFile.exists() &&
            assetFile.length() == newest.length() &&
            assetFile.lastModified() >= newest.lastModified()
        ) {
            println("warp: toolchain asset already current (${newest.length() / 1048576} MB)")
            return@doLast
        }

        assetFile.parentFile.mkdirs()
        newest.copyTo(assetFile, overwrite = true)
        println("warp: bundled ${newest.name} (${newest.length() / 1048576} MB) into assets")
    }
}

tasks.named("preBuild") { dependsOn(syncToolchainAsset) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Conversation storage ────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)   // suspend DAOs and Flow queries
    ksp(libs.androidx.room.compiler)

    // ── On-device build engine ──────────────────────────────────────────
    // Both are pure Java and run on ART inside Warp — no JVM needed for these.
    implementation(libs.r8)      // D8: .class -> .dex
    implementation(libs.apksig)  // signs the APKs Warp builds

    // Git on the phone — §5n. Pure Java, so it runs on ART like the two above.
    implementation(libs.jgit)
    implementation(libs.jgit.ssh)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
