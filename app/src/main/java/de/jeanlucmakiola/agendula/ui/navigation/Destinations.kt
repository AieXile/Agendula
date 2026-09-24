package de.jeanlucmakiola.agendula.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.TaskFilter

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

    /** App preferences, on the hub. */
    const val SETTINGS = "settings"

    /** An `.ics` handed to us by another app. */
    object Import {
        const val ARG_URI = "uri"
        const val route = "import?$ARG_URI={$ARG_URI}"
        val arguments = listOf(navArgument(ARG_URI) { type = NavType.StringType; nullable = true; defaultValue = null })
        fun build(uri: android.net.Uri): String = "import?$ARG_URI=${android.net.Uri.encode(uri.toString())}"
    }

    /** App preferences, optionally opened on one sub-screen. */
    object Settings {
        const val ARG_SECTION = "section"
        const val ARG_ACCOUNT = "account"
        const val route = "settings?$ARG_SECTION={$ARG_SECTION}&$ARG_ACCOUNT={$ARG_ACCOUNT}"
        const val SECTION_ACCOUNTS = "Accounts"

        val arguments = listOf(
            navArgument(ARG_SECTION) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(ARG_ACCOUNT) { type = NavType.LongType; defaultValue = NO_ID },
        )

        fun buildAccount(accountId: Long): String = "settings?$ARG_ACCOUNT=$accountId"

        fun build(section: String? = null): String =
            if (section == null) SETTINGS else "settings?$ARG_SECTION=$section"
    }

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

    /** A single task's detail; `occ` (epoch millis) picks one occurrence of a series. */
    object TaskDetail {
        const val ARG_TASK_ID = "taskId"

        /** The occurrence of a recurring task, as epoch millis; [NO_ID] = the current one. */
        const val ARG_OCC = "occ"
        const val route = "taskDetail/{$ARG_TASK_ID}?$ARG_OCC={$ARG_OCC}"
        val arguments = listOf(
            navArgument(ARG_TASK_ID) { type = NavType.LongType },
            navArgument(ARG_OCC) { type = NavType.LongType; defaultValue = NO_ID },
        )

        fun build(taskId: Long, occurrenceStart: Long? = null): String =
            if (occurrenceStart == null) "taskDetail/$taskId" else "taskDetail/$taskId?$ARG_OCC=$occurrenceStart"
    }

    /**
     * The create / edit form. `taskId` set ([NO_ID] otherwise) ⇒ edit; for a new
     * task, `presetListId` / `parentId` optionally seed the form.
     */
    object TaskEdit {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PRESET_LIST_ID = "presetListId"
        const val ARG_PARENT_ID = "parentId"
        const val ARG_OCCURRENCE = "occ"
        const val ARG_TITLE = "title"
        const val route =
            "taskEdit?$ARG_TASK_ID={$ARG_TASK_ID}" +
                "&$ARG_PRESET_LIST_ID={$ARG_PRESET_LIST_ID}" +
                "&$ARG_PARENT_ID={$ARG_PARENT_ID}" +
                "&$ARG_OCCURRENCE={$ARG_OCCURRENCE}" +
                "&$ARG_TITLE={$ARG_TITLE}"

        val arguments = listOf(
            navArgument(ARG_TASK_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_PRESET_LIST_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_PARENT_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_OCCURRENCE) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_TITLE) { type = NavType.StringType; nullable = true; defaultValue = null },
        )

        fun buildEdit(taskId: Long, occurrenceStart: Long? = null): String =
            if (occurrenceStart == null) "taskEdit?$ARG_TASK_ID=$taskId"
            else "taskEdit?$ARG_TASK_ID=$taskId&$ARG_OCCURRENCE=$occurrenceStart"

        fun buildNew(presetListId: Long? = null, parentId: Long? = null, title: String? = null): String {
            val params = buildList {
                if (presetListId != null) add("$ARG_PRESET_LIST_ID=$presetListId")
                if (parentId != null) add("$ARG_PARENT_ID=$parentId")
                if (!title.isNullOrBlank()) add("$ARG_TITLE=${android.net.Uri.encode(title)}")
            }
            return if (params.isEmpty()) "taskEdit" else "taskEdit?${params.joinToString("&")}"
        }
    }
}
