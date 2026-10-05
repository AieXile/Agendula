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
        // release itself. A versionName of X.Y.Z-beta.N pushed to a release/*
        // branch instead cuts a Codeberg-only pre-release (beta.yaml).
        // versionCode is derived from versionName by scripts/version_info.sh
        // (1.0.x: 1.0.0 -> 10000; from 1.1.0: 1.1.0-beta.1 -> 1010001,
        // 1.1.0 -> 1010099), and CI fails if the committed one doesn't match.
        // See docs/RELEASING.md.
        versionCode = 10000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The sync-adapter and authenticator XML descriptors cannot read
        // BuildConfig, so the two identifiers they need are generated here.
        // Derived from applicationId so the debug and releaseTest builds get
        // their own and can be installed alongside the real app without their
        // accounts colliding. Must stay in step with SyncContract.
        resValue("string", "account_type", "de.jeanlucmakiola.agendula.caldav")
        resValue("string", "sync_authority", "de.jeanlucmakiola.agendula.sync")
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
            resValue("string", "account_type", "de.jeanlucmakiola.agendula.debug.caldav")
            resValue("string", "sync_authority", "de.jeanlucmakiola.agendula.debug.sync")
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
            resValue("string", "account_type", "de.jeanlucmakiola.agendula.releasetest.caldav")
            resValue("string", "sync_authority", "de.jeanlucmakiola.agendula.releasetest.sync")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The account type and sync authority are generated per variant so the
        // debug and releaseTest builds do not fight the real app over ownership
        // of an account type. AGP 9 requires opting in.
        resValues = true
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
        // Ship the prebuilt .so files (datastore's shared counter) exactly as the
        // AAR has them. AGP strips them only when an NDK happens to be installed,
        // so CI and F-Droid's buildserver would otherwise disagree on the bytes.
        jniLibs {
            keepDebugSymbols += "**/*.so"
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

    // MigrationTestHelper reads the exported schemas out of the test APK's
    // assets, so app/schemas/ has to ship with the instrumented tests.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Export each Room schema version to app/schemas/ and commit it. That JSON is
// what MigrationTestHelper reads to build an old database and migrate it, so
// without it a migration can only be tested by hand.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Not a dependency we use directly — lifecycle already drags it in at 1.7.3.
    // AGP's consistent resolution then pins androidTest to the app classpath, and
    // room-testing's MigrationTestHelper needs 1.8+ to deserialize the exported
    // schema; on 1.7.3 it dies with an AbstractMethodError. Raise it in one place.
    constraints {
        implementation(libs.kotlinx.serialization.json)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
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
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    ksp(libs.hilt.compiler)

    // Sync runs in WorkManager, triggered *through* the sync-adapter framework.
    // hilt-work supplies the HiltWorkerFactory; its compiler generates the
    // @HiltWorker plumbing.
    implementation(libs.androidx.work.runtime.ktx)
    // Custom Tabs: the Nextcloud login flow hands the browser an approval page.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Push sync: a UnifiedPush distributor delivers the server's WebDAV-Push messages.
    implementation(libs.unifiedpush.connector)

    // RFC 5545 recurrence expansion, in-process; see the catalog for the pin.
    implementation(libs.dmfs.lib.recur)

    // Vendored dav4jvm — the CalDAV protocol layer. See dav/PROVENANCE.md.
    implementation(project(":dav"))
    // Discovery, auth and Nextcloud Login Flow v2.
    implementation(project(":caldav"))
    // :dav gets org.xmlpull.v1 from the Android framework at runtime and declares
    // xpp3 compileOnly, which is not transitive. Unit tests run on a plain JVM
    // with no framework, and android.jar's stub factory returns null under
    // isReturnDefaultValues — so anything touching XmlUtils would NPE without a
    // real implementation here.
    testImplementation(libs.xpp3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.floret.core.time)
    implementation(libs.floret.core.reminders)
    implementation(libs.floret.core.locale)
    implementation(libs.floret.core.crash)
    implementation(libs.floret.identity)
    implementation(libs.floret.components)

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
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
