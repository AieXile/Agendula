package de.jeanlucmakiola.floret.data.tasks

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tasks provider Floret can talk to. The same dmfs `TaskProvider` backs every
 * candidate, so the [TasksContract] columns apply regardless of which is present.
 */
data class TaskProvider(
    val authority: String,
    val readPermission: String,
    val writePermission: String,
    val packageName: String? = null,
)

/**
 * The A/B seam. Detects which tasks provider is installed at runtime and which
 * permission set it needs, so nothing above the data layer hardcodes an
 * authority. Under Posture B (bundled provider) this simply finds our own
 * `org.dmfs.tasks` first. See docs/PLAN.md.
 */
@Singleton
class ProviderResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The active provider, or `null` when no tasks provider is installed. */
    fun resolve(): TaskProvider? {
        for (candidate in CANDIDATES) {
            val info = context.packageManager.resolveContentProvider(candidate.authority, 0)
                ?: continue
            return candidate.copy(packageName = info.packageName)
        }
        return null
    }

    fun hasPermission(provider: TaskProvider): Boolean =
        granted(provider.readPermission) && granted(provider.writePermission)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Verified on-device: tasks.org exposes `org.tasks.opentasks` backed by
         * `org.dmfs.provider.tasks.TaskProvider`, guarded by
         * `org.tasks.permission.*` (dangerous). OpenTasks uses `org.dmfs.tasks`
         * + `org.dmfs.permission.*`. OpenTasks is listed first as the canonical
         * authority; on a device with only one installed, order is moot.
         */
        val CANDIDATES: List<TaskProvider> = listOf(
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
