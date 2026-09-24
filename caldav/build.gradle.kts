plugins {
    // No version — AGP already puts the Kotlin plugin on the build classpath.
    id("org.jetbrains.kotlin.jvm")
}

// Our CalDAV protocol layer: discovery, auth, Nextcloud Login Flow v2. MIT, and
// deliberately a separate module from the MPL-2.0 vendored `:dav`.
//
// A plain JVM module, like `:dav`, and for the same two reasons: it enforces "no
// Android types" at compile time rather than by discipline, and it lets the whole
// protocol layer be tested against MockWebServer on the JVM. Anything that needs
// the platform — Keystore, AccountManager, Custom Tabs — belongs in `:app`.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":dav"))
    implementation(libs.dnsjava)
    // Runtime API only (Json.parseToJsonElement) — no @Serializable, so the
    // serialization compiler plugin is not needed here.
    implementation(libs.kotlinx.serialization.json)

    compileOnly(libs.xpp3)
    testImplementation(libs.xpp3)
    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.truth)
}
