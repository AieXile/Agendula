package de.jeanlucmakiola.floret.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import de.jeanlucmakiola.floret.domain.SmartList
import de.jeanlucmakiola.floret.domain.TaskFilter

/**
 * String-based route table for the app's [androidx.navigation.NavHost].
 *
 * Routes are kept stringly-typed (rather than the type-safe `@Serializable`
 * API) so we don't have to pull in the kotlinx-serialization Gradle plugin for
 * four small destinations. Each destination exposes a `route` template (for
 * `composable(...)`) and a builder that fills the arguments (for `navigate`).
 *
 * Argument convention: optional `Long`s default to [NO_ID] (`-1`) rather than
 * being nullable, which sidesteps the lack of a nullable-Long [NavType].
 */
object Dest {

    const val NO_ID = -1L

    /** Home — smart lists + the user's lists. */
    const val LISTS = "lists"

    /**
     * One [TaskFilter]'s tasks. Carries *either* `listId` (a real list) *or*
     * `smart` (a [SmartList] name); whichever is set decides the filter.
     */
    object TaskList {
        const val ARG_LIST_ID = "listId"
        const val ARG_SMART = "smart"
        const val route = "taskList?$ARG_LIST_ID={$ARG_LIST_ID}&$ARG_SMART={$ARG_SMART}"

        val arguments = listOf(
            navArgument(ARG_LIST_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_SMART) { type = NavType.StringType; nullable = true; defaultValue = null },
        )

        fun build(filter: TaskFilter): String = when (filter) {
            is TaskFilter.OfList -> "taskList?$ARG_LIST_ID=${filter.listId}"
            is TaskFilter.Smart -> "taskList?$ARG_SMART=${filter.list.name}"
        }

        /** Reconstruct the filter from a destination's arguments. */
        fun filterOf(smart: String?, listId: Long): TaskFilter =
            if (smart != null) TaskFilter.Smart(SmartList.valueOf(smart))
            else TaskFilter.OfList(listId)
    }

    /** A single task's detail. */
    object TaskDetail {
        const val ARG_TASK_ID = "taskId"
        const val route = "taskDetail/{$ARG_TASK_ID}"
        val arguments = listOf(navArgument(ARG_TASK_ID) { type = NavType.LongType })
        fun build(taskId: Long): String = "taskDetail/$taskId"
    }

    /**
     * The create / edit form. `taskId` set ([NO_ID] otherwise) ⇒ edit; for a new
     * task, `presetListId` / `parentId` optionally seed the form.
     */
    object TaskEdit {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PRESET_LIST_ID = "presetListId"
        const val ARG_PARENT_ID = "parentId"
        const val route =
            "taskEdit?$ARG_TASK_ID={$ARG_TASK_ID}" +
                "&$ARG_PRESET_LIST_ID={$ARG_PRESET_LIST_ID}" +
                "&$ARG_PARENT_ID={$ARG_PARENT_ID}"

        val arguments = listOf(
            navArgument(ARG_TASK_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_PRESET_LIST_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_PARENT_ID) { type = NavType.LongType; defaultValue = NO_ID },
        )

        fun buildEdit(taskId: Long): String = "taskEdit?$ARG_TASK_ID=$taskId"

        fun buildNew(presetListId: Long? = null, parentId: Long? = null): String {
            val params = buildList {
                if (presetListId != null) add("$ARG_PRESET_LIST_ID=$presetListId")
                if (parentId != null) add("$ARG_PARENT_ID=$parentId")
            }
            return if (params.isEmpty()) "taskEdit" else "taskEdit?${params.joinToString("&")}"
        }
    }
}
