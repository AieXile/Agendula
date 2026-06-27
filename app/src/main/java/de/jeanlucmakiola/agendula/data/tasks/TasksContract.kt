package de.jeanlucmakiola.agendula.data.tasks

import android.net.Uri

/**
 * The subset of the OpenTasks `TaskContract` that Agendula uses, vendored as
 * Kotlin constants. Derived from `org.dmfs.tasks.contract.TaskContract`
 * (Apache-2.0, dmfs GmbH):
 * https://github.com/dmfs/opentasks/blob/master/opentasks-contract/src/main/java/org/dmfs/tasks/contract/TaskContract.java
 *
 * The provider **authority** is supplied at runtime by [ProviderResolver] — it
 * is `org.tasks.opentasks` when tasks.org is installed and `org.dmfs.tasks` for
 * OpenTasks. Both run the identical dmfs `TaskProvider`, so every column name
 * here applies verbatim to either (verified on-device against tasks.org's
 * `org.dmfs.provider.tasks.TaskProvider`).
 *
 * Column names are plain strings, so the mappers that use them are unit-testable
 * on the JVM without a real Cursor.
 */
object TasksContract {

    // --- URI query parameters -------------------------------------------------
    const val CALLER_IS_SYNCADAPTER = "caller_is_syncadapter"
    const val PARAM_ACCOUNT_NAME = "account_name"
    const val PARAM_ACCOUNT_TYPE = "account_type"
    const val LOAD_PROPERTIES = "load_properties"

    /** Account used for local, device-only task lists Agendula owns. */
    const val LOCAL_ACCOUNT_NAME = "Local"
    const val LOCAL_ACCOUNT_TYPE = "org.dmfs.account.LOCAL"

    /** The `tasklists` table — one row per task list. */
    object Lists {
        const val PATH = "tasklists"
        const val ID = "_id"
        const val NAME = "list_name"
        const val COLOR = "list_color"
        const val ACCESS_LEVEL = "list_access_level"
        const val VISIBLE = "visible"
        const val SYNC_ENABLED = "sync_enabled"
        const val OWNER = "list_owner"
        const val ACCOUNT_NAME = "account_name"
        const val ACCOUNT_TYPE = "account_type"
    }

    /** The `tasks` table — the editable task rows (write target). */
    object Tasks {
        const val PATH = "tasks"
        const val ID = "_id"
        const val LIST_ID = "list_id"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val LOCATION = "location"
        const val URL = "url"
        const val PRIORITY = "priority"
        const val CLASSIFICATION = "class"
        const val STATUS = "status"
        const val PERCENT_COMPLETE = "percent_complete"
        const val COMPLETED = "completed"
        const val IS_CLOSED = "is_closed"
        const val IS_NEW = "is_new"
        const val TASK_COLOR = "task_color"
        const val DTSTART = "dtstart"
        const val DUE = "due"
        const val DURATION = "duration"
        const val IS_ALLDAY = "is_allday"
        const val TZ = "tz"
        const val RRULE = "rrule"
        const val PARENT_ID = "parent_id"
        const val SORTING = "sorting"
        const val CREATED = "created"
        const val LAST_MODIFIED = "last_modified"

        // Auto-derived from the owning list (read-only on this table).
        const val LIST_NAME = "list_name"
        const val LIST_COLOR = "list_color"
        const val ACCOUNT_NAME = "account_name"
        const val ACCOUNT_TYPE = "account_type"

        const val UID = "_uid"
        const val DELETED = "_deleted"
    }

    /**
     * The `instances` view — one row per task occurrence, with recurrence
     * resolved and absolute [INSTANCE_START]/[INSTANCE_DUE] timestamps. Inherits
     * every [Tasks] column (title, status, …) plus the instance-only columns
     * below. This is Agendula's read source.
     */
    object Instances {
        const val PATH = "instances"
        const val TASK_ID = "task_id"
        const val INSTANCE_START = "instance_start"
        const val INSTANCE_DUE = "instance_due"
        const val INSTANCE_START_SORTING = "instance_start_sorting"
        const val INSTANCE_DUE_SORTING = "instance_due_sorting"
        const val DISTANCE_FROM_CURRENT = "distance_from_current"
        const val IS_RECURRING = "is_recurring"
    }

    // --- status values (TaskColumns.STATUS_*) --------------------------------
    const val STATUS_NEEDS_ACTION = 0
    const val STATUS_IN_PROCESS = 1
    const val STATUS_COMPLETED = 2
    const val STATUS_CANCELLED = 3

    /** Priority 0 means "no priority"; 1 is highest, 9 lowest (iCalendar). */
    const val PRIORITY_NONE = 0

    fun authorityUri(authority: String): Uri = Uri.parse("content://$authority")
    fun listsUri(authority: String): Uri = Uri.parse("content://$authority/${Lists.PATH}")
    fun tasksUri(authority: String): Uri = Uri.parse("content://$authority/${Tasks.PATH}")
    fun instancesUri(authority: String): Uri = Uri.parse("content://$authority/${Instances.PATH}")

    /** Append the sync-adapter params required to write local-account rows. */
    fun asSyncAdapter(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(PARAM_ACCOUNT_NAME, accountName)
            .appendQueryParameter(PARAM_ACCOUNT_TYPE, accountType)
            .build()
}
