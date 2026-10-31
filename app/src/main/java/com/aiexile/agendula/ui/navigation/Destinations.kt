package com.aiexile.agendula.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.TaskFilter

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
     * One [TaskFilter]'s tasks. Carries one of `listId` (a real list), `smart`
     * (a [SmartList] name), `tag` or `filterId` (a saved filter); whichever is
     * set decides the filter.
     */
    object TaskList {
        const val ARG_LIST_ID = "listId"
        const val ARG_SMART = "smart"
        const val ARG_TAG = "tag"
        const val ARG_FILTER_ID = "filterId"
        const val route = "taskList?$ARG_LIST_ID={$ARG_LIST_ID}&$ARG_SMART={$ARG_SMART}&$ARG_TAG={$ARG_TAG}" +
            "&$ARG_FILTER_ID={$ARG_FILTER_ID}"

        val arguments = listOf(
            navArgument(ARG_LIST_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_SMART) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(ARG_TAG) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(ARG_FILTER_ID) { type = NavType.LongType; defaultValue = NO_ID },
        )

        fun build(filter: TaskFilter): String = when (filter) {
            is TaskFilter.OfList -> "taskList?$ARG_LIST_ID=${filter.listId}"
            is TaskFilter.Smart -> "taskList?$ARG_SMART=${filter.list.name}"
            is TaskFilter.Tagged -> "taskList?$ARG_TAG=${android.net.Uri.encode(filter.tag)}"
            is TaskFilter.Saved -> "taskList?$ARG_FILTER_ID=${filter.id}"
            // Several lists at once only exists for the widget, which never routes here.
            is TaskFilter.OfLists -> error("OfLists has no task-list screen")
        }

        /** Reconstruct the filter from a destination's arguments. */
        fun filterOf(smart: String?, listId: Long, tag: String? = null, filterId: Long = NO_ID): TaskFilter = when {
            smart != null -> TaskFilter.Smart(SmartList.valueOf(smart))
            tag != null -> TaskFilter.Tagged(tag)
            filterId != NO_ID -> TaskFilter.Saved(filterId)
            else -> TaskFilter.OfList(listId)
        }
    }

    /** Creates a saved filter, or edits the one `filterId` names. */
    object FilterEdit {
        const val ARG_FILTER_ID = "filterId"
        const val route = "filterEdit?$ARG_FILTER_ID={$ARG_FILTER_ID}"
        val arguments = listOf(navArgument(ARG_FILTER_ID) { type = NavType.LongType; defaultValue = NO_ID })

        fun build(filterId: Long? = null): String =
            if (filterId == null) "filterEdit" else "filterEdit?$ARG_FILTER_ID=$filterId"
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
     * task, `presetListId` / `parentId` optionally seed the form, and `filterId`
     * fills it so the task lands in that saved filter.
     */
    object TaskEdit {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PRESET_LIST_ID = "presetListId"
        const val ARG_PARENT_ID = "parentId"
        const val ARG_OCCURRENCE = "occ"
        const val ARG_TITLE = "title"
        const val ARG_TAG = "tag"
        const val ARG_FILTER_ID = "filterId"
        const val route =
            "taskEdit?$ARG_TASK_ID={$ARG_TASK_ID}" +
                "&$ARG_PRESET_LIST_ID={$ARG_PRESET_LIST_ID}" +
                "&$ARG_PARENT_ID={$ARG_PARENT_ID}" +
                "&$ARG_OCCURRENCE={$ARG_OCCURRENCE}" +
                "&$ARG_TITLE={$ARG_TITLE}" +
                "&$ARG_TAG={$ARG_TAG}" +
                "&$ARG_FILTER_ID={$ARG_FILTER_ID}"

        val arguments = listOf(
            navArgument(ARG_TASK_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_PRESET_LIST_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_PARENT_ID) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_OCCURRENCE) { type = NavType.LongType; defaultValue = NO_ID },
            navArgument(ARG_TITLE) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(ARG_TAG) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(ARG_FILTER_ID) { type = NavType.LongType; defaultValue = NO_ID },
        )

        fun buildEdit(taskId: Long, occurrenceStart: Long? = null): String =
            if (occurrenceStart == null) "taskEdit?$ARG_TASK_ID=$taskId"
            else "taskEdit?$ARG_TASK_ID=$taskId&$ARG_OCCURRENCE=$occurrenceStart"

        fun buildNew(
            presetListId: Long? = null,
            parentId: Long? = null,
            title: String? = null,
            tag: String? = null,
            filterId: Long? = null,
        ): String {
            val params = buildList {
                if (presetListId != null) add("$ARG_PRESET_LIST_ID=$presetListId")
                if (parentId != null) add("$ARG_PARENT_ID=$parentId")
                if (!title.isNullOrBlank()) add("$ARG_TITLE=${android.net.Uri.encode(title)}")
                if (!tag.isNullOrBlank()) add("$ARG_TAG=${android.net.Uri.encode(tag)}")
                if (filterId != null) add("$ARG_FILTER_ID=$filterId")
            }
            return if (params.isEmpty()) "taskEdit" else "taskEdit?${params.joinToString("&")}"
        }
    }
}
