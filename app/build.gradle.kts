plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ ships Kotlin support built in — the standalone
    // org.jetbrains.kotlin.android plugin must NOT be applied.
    alias(libs.plugins.kotlin.compose)
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── On-device build engine ──────────────────────────────────────────
    // Both are pure Java and run on ART inside Warp — no JVM needed for these.
    implementation(libs.r8)      // D8: .class -> .dex
    implementation(libs.apksig)  // signs the APKs Warp builds

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
