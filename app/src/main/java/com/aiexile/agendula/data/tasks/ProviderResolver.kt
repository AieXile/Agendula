package com.aiexile.agendula.data.tasks

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An external tasks provider Agendula can talk to. Every candidate runs the same
 * dmfs `TaskProvider`, so the [TasksContract] columns apply to either.
 */
data class TaskProvider(
    val authority: String,
    val readPermission: String,
    val writePermission: String,
    val packageName: String? = null,
)

/**
 * Discovers the *external* tasks providers (OpenTasks, tasks.org) that
 * [StorageMode.EXTERNAL] can be pointed at.
 *
 * This used to be the A/B seam between an external provider and one Agendula
 * bundled itself. That second half is gone: [StorageMode.OWN] is a Room database
 * with no authority, no ContentResolver and nothing to permit, so there is
 * nothing here for it to resolve.
 *
 * Which store is active comes from [storageMode]; how that gets decided when the
 * user has not chosen is [autoMode].
 */
@Singleton
class ProviderResolver @Inject constructor(
    private val environment: ProviderEnvironment,
) {

    private val modeListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * The user's explicit choice, or `null` while they have not made one (which is
     * the normal state — most people never open Settings). Kept as a plain field
     * rather than read from DataStore on demand because [resolve] is called from
     * synchronous data-source code on every query, including from the main thread
     * via `providerStatus()`. [StorageModeHolder] owns keeping it current.
     *
     * Assigning a *different* mode notifies [onModeChanged]: every live store
     * observer is bound to one store and has to be moved across.
     */
    @Volatile
    var storageMode: StorageMode? = null
        set(value) {
            val changed = field != value
            field = value
            if (changed) modeListeners.forEach { it() }
        }

    /**
     * Observe switches between stores. Fires on the thread that set [storageMode]
     * — [StorageModeHolder]'s collector — so listeners must be cheap and must not
     * block.
     */
    fun onModeChanged(listener: () -> Unit): AutoCloseable {
        modeListeners += listener
        return AutoCloseable { modeListeners -= listener }
    }

    /** The active store, resolving the undecided case through [autoMode]. */
    fun mode(): StorageMode = storageMode ?: autoMode()

    /**
     * The provider to query, or `null` — either because [StorageMode.OWN] is
     * active and there is no provider involved at all, or because
     * [StorageMode.EXTERNAL] is and none is installed. Callers that need to tell
     * those apart ask [mode].
     */
    fun resolve(): TaskProvider? = when (mode()) {
        StorageMode.OWN -> null
        StorageMode.EXTERNAL -> resolveExternal()
    }

    /**
     * What to use when the user has not chosen — and the one piece of real
     * judgement in this class, because getting it wrong loses people their data.
     *
     * Ranking our own store first unconditionally would be wrong: someone who
     * has been using Agendula over OpenTasks since 0.3.x would update, land on an
     * empty database, and reasonably conclude their tasks were deleted.
     *
     * So the tell is **whether we already hold an external provider's runtime
     * permission**. That is a dangerous permission — it can only be there because
     * a previous version asked and the user agreed, which is precisely the
     * definition of "this person is an existing Posture A user". A fresh install
     * never holds it, and gets our own store.
     *
     * Deliberately cheap and synchronous: a PackageManager lookup and a permission
     * check, no database probe. Settings overrides it either way.
     */
    fun autoMode(): StorageMode {
        val external = resolveExternal()
        return if (external != null && hasPermission(external)) StorageMode.EXTERNAL else StorageMode.OWN
    }

    /** The first installed external candidate, or `null` when none is present. */
    fun resolveExternal(): TaskProvider? {
        for (candidate in EXTERNAL_CANDIDATES) {
            val packageName = environment.packageDeclaring(candidate.authority) ?: continue
            return candidate.copy(packageName = packageName)
        }
        return null
    }

    fun hasPermission(provider: TaskProvider): Boolean =
        environment.isGranted(provider.readPermission) && environment.isGranted(provider.writePermission)

    /**
     * Whether the active store can be read at all.
     *
     * [StorageMode.OWN] always can — it is our own database, with nothing to
     * install and nothing to grant. Only [StorageMode.EXTERNAL] can be
     * unreadable. Callers that gate on `resolve() != null` instead get this wrong
     * the moment OWN is active, because OWN resolves to no provider by design.
     */
    fun canReadStore(): Boolean = when (mode()) {
        StorageMode.OWN -> true
        StorageMode.EXTERNAL -> resolveExternal()?.let(::hasPermission) == true
    }

    companion object {
        /**
         * Verified on-device: tasks.org exposes `org.tasks.opentasks` backed by
         * `org.dmfs.provider.tasks.TaskProvider`, guarded by `org.tasks.permission.*`
         * (dangerous). OpenTasks uses `org.dmfs.tasks` + `org.dmfs.permission.*`.
         * OpenTasks is listed first as the canonical authority; on a device with
         * only one installed, order is moot.
         */
        val EXTERNAL_CANDIDATES: List<TaskProvider> = listOf(
            TaskProvider(
                authority = "org.dmfs.tasks",
                readPermission = "org.dmfs.permission.READ_TASKS",
                writePermission = "org.dmfs.permission.WRITE_TASKS",
            ),
            TaskProvider(
                authority = "org.tasks.opentasks",
                readPermission = "org.tasks.permission.READ_TASKS",
                writePermission = "org.tasks.permission.WRITE_TASKS",
            ),
        )
    }
}
