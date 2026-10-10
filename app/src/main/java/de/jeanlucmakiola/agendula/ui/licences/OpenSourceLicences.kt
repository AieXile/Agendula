package de.jeanlucmakiola.agendula.ui.licences

import de.jeanlucmakiola.agendula.BuildConfig

/**
 * The third-party code Agendula ships, and what each licence obliges us to say.
 *
 * ⚠️ This screen is a **licence obligation, not an about-page nicety**. Until it
 * exists the app is in plain violation:
 *
 * - **MPL-2.0 §3.2(a)** requires that recipients of the *executable* be told how
 *   to obtain the source of the covered files, and **§3.4** that the file headers
 *   be retained. That is dav4jvm, which we vendor — vendoring makes the
 *   obligation ours rather than a transitive dependency's.
 * - **BSD-3-Clause** requires the copyright notice and disclaimer to be
 *   reproduced "in the documentation and/or other materials provided with the
 *   distribution", which for an app is exactly this.
 * - **Apache-2.0 §4(d)** propagates any `NOTICE` file, and §4(a) requires the
 *   licence to travel with the work.
 *
 * Hand-maintained on purpose. Generators read POM metadata, and POM metadata is
 * routinely wrong — the licence name is often not an SPDX id and the licence URL
 * frequently 404s, which produces confidently empty output. A short honest list
 * beats a long generated one that omits the entry that mattered.
 *
 * **When adding a dependency, add it here.** There is no automated check that
 * can tell you that you forgot.
 */
data class Attribution(
    val name: String,
    val copyright: String,
    val licence: Licence,
    /** Where the source can actually be obtained — MPL-2.0 §3.2(a)'s requirement. */
    val sourceUrl: String,
    /** Only in the `full` flavor, which carries CalDAV sync and push. */
    val syncOnly: Boolean = false,
)

enum class Licence(val spdxId: String, val url: String) {
    APACHE_2("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    BSD_3("BSD-3-Clause", "https://opensource.org/license/bsd-3-clause"),
    MPL_2("MPL-2.0", "https://mozilla.org/MPL/2.0/"),
    MIT("MIT", "https://opensource.org/license/mit"),
}

object OpenSourceLicences {

    /**
     * ⚠️ dav4jvm is **vendored**, not depended on — see `dav/PROVENANCE.md`. That
     * makes MPL-2.0 §3.2(a) directly ours: we distribute modified covered files
     * inside our binary, so we must say where their source is. The upstream URL
     * satisfies it only together with `PROVENANCE.md`, which lists every change
     * we made; both are named below.
     */
    val ALL: List<Attribution> = listOf(
        Attribution(
            name = "dav4jvm (vendored, modified — see dav/PROVENANCE.md)",
            copyright = "© bitfire web engineering (Ricki Hirner, Bernhard Stockmann)",
            licence = Licence.MPL_2,
            sourceUrl = "https://github.com/bitfireAT/dav4jvm",
            syncOnly = true,
        ),
        Attribution(
            name = "dnsjava",
            copyright = "© Brian Wellington and the dnsjava contributors",
            licence = Licence.BSD_3,
            sourceUrl = "https://github.com/dnsjava/dnsjava",
            syncOnly = true,
        ),
        Attribution(
            name = "UnifiedPush Android connector",
            copyright = "© UnifiedPush contributors",
            licence = Licence.APACHE_2,
            sourceUrl = "https://codeberg.org/UnifiedPush/android-connector",
            syncOnly = true,
        ),
        Attribution(
            name = "Tink",
            copyright = "© Google LLC",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/tink-crypto/tink-java",
            syncOnly = true,
        ),
        Attribution(
            name = "OkHttp",
            copyright = "© Square, Inc.",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/square/okhttp",
            syncOnly = true,
        ),
        Attribution(
            name = "Okio",
            copyright = "© Square, Inc.",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/square/okio",
        ),
        Attribution(
            name = "lib-recur",
            copyright = "© dmfs GmbH",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/dmfs/lib-recur",
        ),
        Attribution(
            name = "Kotlin, kotlinx.coroutines, kotlinx.datetime, kotlinx.serialization",
            copyright = "© JetBrains s.r.o. and Kotlin Programming Language contributors",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/JetBrains/kotlin",
        ),
        Attribution(
            name = "AndroidX, Jetpack Compose, Room, WorkManager, DataStore, Glance",
            copyright = "© The Android Open Source Project",
            licence = Licence.APACHE_2,
            sourceUrl = "https://cs.android.com/androidx/platform/frameworks/support",
        ),
        Attribution(
            name = "Dagger and Hilt",
            copyright = "© Google LLC and The Dagger Authors",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/google/dagger",
        ),
        Attribution(
            name = "Material Design icons",
            copyright = "© Google LLC",
            licence = Licence.APACHE_2,
            sourceUrl = "https://github.com/google/material-design-icons",
        ),
        Attribution(
            name = "floret-kit",
            copyright = "© Jean-Luc Makiola",
            licence = Licence.MIT,
            sourceUrl = "https://codeberg.org/jlmakiola/floret-kit",
        ),
    ).filter { BuildConfig.SYNC_ENABLED || !it.syncOnly }
}
