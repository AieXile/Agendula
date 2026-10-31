package com.aiexile.agendula.data.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The storage-mode decision, which is the part of [ProviderResolver] with real
 * consequences: pick wrong for a returning user and the app opens on an empty
 * store where their tasks used to be.
 */
class ProviderResolverTest {

    /**
     * A [ProviderEnvironment] with no Android in it.
     *
     * @param installed authority -> declaring package, i.e. what is on the device.
     * @param granted permissions this app currently holds.
     */
    private class FakeEnvironment(
        val installed: Map<String, String> = emptyMap(),
        val granted: Set<String> = emptySet(),
    ) : ProviderEnvironment {
        override fun packageDeclaring(authority: String): String? = installed[authority]
        override fun isGranted(permission: String): Boolean = permission in granted
        override fun appLabel(packageName: String): String? = packageName
    }

    private val openTasks = ProviderResolver.EXTERNAL_CANDIDATES.first { it.authority == "org.dmfs.tasks" }

    private fun resolver(
        installed: Map<String, String> = emptyMap(),
        granted: Set<String> = emptySet(),
        mode: StorageMode? = null,
    ) = ProviderResolver(FakeEnvironment(installed, granted)).apply { storageMode = mode }

    private val openTasksInstalled = mapOf("org.dmfs.tasks" to "org.dmfs.tasks")
    private val openTasksGranted = setOf(openTasks.readPermission, openTasks.writePermission)

    @Nested
    inner class OwnStore {

        @Test
        fun `is readable without anything installed or granted`() {
            // Guards a real regression: the reminder engine used to gate on
            // resolve() != null, which is exactly what OWN returns, so every
            // reminder was cleared the moment our own store became the default.
            assertThat(resolver(mode = StorageMode.OWN).canReadStore()).isTrue()
        }

        @Test
        fun `resolves to no provider at all`() {
            // Room has no authority and no ContentResolver, so there is nothing
            // here to resolve — which is the point. Callers that need to tell this
            // apart from "External, none installed" ask mode().
            val resolver = resolver(mode = StorageMode.OWN)
            assertThat(resolver.resolve()).isNull()
            assertThat(resolver.mode()).isEqualTo(StorageMode.OWN)
        }
    }

    @Nested
    inner class AutoMode {

        @Test
        fun `a fresh install with nothing else present gets our own store`() {
            assertThat(resolver().autoMode()).isEqualTo(StorageMode.OWN)
        }

        @Test
        fun `an upgrading user who already granted OpenTasks stays on it`() {
            // Holding a dangerous permission means a previous version asked and they
            // agreed — the signature of an existing Posture A user. Sending them to
            // our empty bundled store would read as data loss.
            val resolver = resolver(installed = openTasksInstalled, granted = openTasksGranted)
            assertThat(resolver.autoMode()).isEqualTo(StorageMode.EXTERNAL)
            assertThat(resolver.resolve()?.authority).isEqualTo("org.dmfs.tasks")
        }

        @Test
        fun `OpenTasks merely installed is not enough`() {
            // Someone who has OpenTasks for unrelated reasons, and never granted us
            // anything, has no data with us there. Our own store is right for them.
            assertThat(resolver(installed = openTasksInstalled).autoMode()).isEqualTo(StorageMode.OWN)
        }

        @Test
        fun `a half-granted external provider does not count`() {
            val resolver = resolver(
                installed = openTasksInstalled,
                granted = setOf(openTasks.readPermission),
            )
            assertThat(resolver.autoMode()).isEqualTo(StorageMode.OWN)
        }
    }

    @Nested
    inner class ExplicitChoice {

        @Test
        fun `overrides the automatic answer in both directions`() {
            val wouldBeExternal = FakeEnvironment(openTasksInstalled, openTasksGranted)

            val forcedOwn = ProviderResolver(wouldBeExternal).apply { storageMode = StorageMode.OWN }
            assertThat(forcedOwn.mode()).isEqualTo(StorageMode.OWN)
            assertThat(forcedOwn.resolve()).isNull()

            val forcedExternal = ProviderResolver(FakeEnvironment()).apply { storageMode = StorageMode.EXTERNAL }
            assertThat(forcedExternal.mode()).isEqualTo(StorageMode.EXTERNAL)
            assertThat(forcedExternal.resolve()).isNull()
        }

        @Test
        fun `external with no provider installed resolves to nothing`() {
            // Drives the "install a tasks provider" gate rather than silently
            // falling back to our own store behind the user's back.
            assertThat(resolver(mode = StorageMode.EXTERNAL).resolve()).isNull()
        }

        @Test
        fun `external is unreadable until a provider is installed and granted`() {
            assertThat(resolver(mode = StorageMode.EXTERNAL).canReadStore()).isFalse()
            assertThat(
                resolver(installed = openTasksInstalled, mode = StorageMode.EXTERNAL).canReadStore(),
            ).isFalse()
            assertThat(
                resolver(
                    installed = openTasksInstalled,
                    granted = openTasksGranted,
                    mode = StorageMode.EXTERNAL,
                ).canReadStore(),
            ).isTrue()
        }

        @Test
        fun `external still requires the runtime permission`() {
            val resolver = resolver(installed = openTasksInstalled, mode = StorageMode.EXTERNAL)
            val provider = resolver.resolve()
            assertThat(provider).isNotNull()
            assertThat(resolver.hasPermission(provider!!)).isFalse()
        }
    }

    @Nested
    inner class ExternalCandidates {

        @Test
        fun `prefer OpenTasks over tasks_org when both are installed`() {
            val resolver = resolver(
                installed = mapOf(
                    "org.dmfs.tasks" to "org.dmfs.tasks",
                    "org.tasks.opentasks" to "org.tasks",
                ),
                mode = StorageMode.EXTERNAL,
            )
            assertThat(resolver.resolve()?.authority).isEqualTo("org.dmfs.tasks")
        }

        @Test
        fun `fall through to tasks_org when OpenTasks is absent`() {
            val resolver = resolver(
                installed = mapOf("org.tasks.opentasks" to "org.tasks"),
                mode = StorageMode.EXTERNAL,
            )
            assertThat(resolver.resolve()?.packageName).isEqualTo("org.tasks")
        }

        @Test
        fun `a mode change notifies listeners once, and only on a real change`() {
            // What store observers hang off: a live flow is bound to one store, so
            // it has to be told when the store underneath it is swapped.
            val resolver = resolver(mode = StorageMode.OWN)
            var fired = 0
            val handle = resolver.onModeChanged { fired++ }

            resolver.storageMode = StorageMode.OWN
            assertThat(fired).isEqualTo(0)

            resolver.storageMode = StorageMode.EXTERNAL
            assertThat(fired).isEqualTo(1)

            handle.close()
            resolver.storageMode = StorageMode.OWN
            assertThat(fired).isEqualTo(1)
        }

        @Test
        fun `never name an authority of ours`() {
            // EXTERNAL must mean "somebody else's store", and Agendula publishes no
            // provider at all any more.
            assertThat(
                ProviderResolver.EXTERNAL_CANDIDATES.none {
                    it.authority.startsWith("com.aiexile.agendula")
                },
            ).isTrue()
        }
    }
}
