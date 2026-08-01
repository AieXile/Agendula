import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "de.jeanlucmakiola.agendula"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.jeanlucmakiola.agendula"
        minSdk = 29
        targetSdk = 36
        // These committed values ARE the source of truth for a release: merging
        // a bumped versionName into main triggers .gitea/workflows/release.yaml,
        // which builds this version and then creates the matching vX.Y.Z tag +
        // release itself (versionCode is pinned to MAJOR*10000 + MINOR*100 +
        // PATCH from versionName, e.g. 0.2.0 -> 200). The Gitea release is marked
        // as a pre-release while MAJOR is 0. See docs/RELEASING.md.
        versionCode = 302
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Keep release builds reproducible for F-Droid: don't let AGP embed
            // build-environment git metadata (META-INF/version-control-info.textproto),
            // whose `revision`/path content varies by build machine and is the only
            // thing that otherwise differs from a clean from-source rebuild.
            vcsInfo { include = false }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        // A locally-installable twin of `release`: same R8 shrinking + obfuscation
        // and resource shrinking, but debug-signed and given its own applicationId
        // suffix so it installs alongside both the production app (signed with the
        // real key) and the debug build. Used to smoke-test a release candidate on
        // a real device before merging to main — R8-only breakage and first-run/
        // permission states don't surface in the unminified debug build, nor on a
        // device that already holds the permission. Never published. See
        // docs/RELEASING.md.
        create("releaseTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasetest"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Don't embed AGP's dependency-metadata block in the APK signing block. It's
    // a Play-oriented blob, and F-Droid's reproducible-build scanner rejects any
    // "extra signing block" — so leaving it in blocks publishing to the official
    // repo. It lives in the signing block, not the zip entries, so disabling it
    // doesn't change the build output (reproducibility is unaffected).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Community translations are expected to be partial — a missing string
        // falls back to the English base at runtime — so don't fail the build on
        // it. Likewise a translated <plurals> may not fill every CLDR quantity
        // form its locale defines (e.g. Arabic needs "zero"); the missing form
        // falls back to "other" at runtime, so MissingQuantity is informational
        // too. Stale/extra keys (ExtraTranslation) stay fatal; scripts/
        // check_translations.py guards the same invariants with clearer,
        // translator-facing messages.
        informational += listOf("MissingTranslation", "MissingQuantity")
    }

    testOptions {
        unitTests {
            all { it.useJUnitPlatform() }
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    implementation("de.jeanlucmakiola.floret:core-time")
    implementation("de.jeanlucmakiola.floret:core-reminders")
    implementation("de.jeanlucmakiola.floret:core-locale")
    implementation("de.jeanlucmakiola.floret:core-crash")
    implementation("de.jeanlucmakiola.floret:identity")
    implementation("de.jeanlucmakiola.floret:components")

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
