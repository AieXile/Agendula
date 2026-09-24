plugins {
    // No version: AGP already puts the Kotlin plugin on the build classpath, and
    // asking for one here fails resolution ("already on the classpath with an
    // unknown version"). The version is the catalogue's `kotlin`, via AGP.
    id("org.jetbrains.kotlin.jvm")
}

// A plain JVM library on purpose: the vendored tree has no Android imports and
// must not gain any. That keeps it portable — this layer is earmarked for
// floret-kit's `core-dav`, and Calendula needs the same primitives.
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
    api(libs.okhttp)

    // Android supplies org.xmlpull.v1 in the framework, so the 371 KB xpp3 jar is
    // a compile-time stand-in only and never reaches the APK. The unit tests run
    // on a plain JVM, which has no framework, so they get the real thing.
    compileOnly(libs.xpp3)
    testImplementation(libs.xpp3)

    // Upstream's own suite, vendored with the code. It is JUnit 4 while :app is
    // JUnit 5; rewriting it would forfeit the regression coverage that makes
    // vendoring safe, which is the same call provider/PROVENANCE.md made.
    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
