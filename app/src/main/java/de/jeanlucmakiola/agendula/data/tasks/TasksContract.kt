package de.jeanlucmakiola.agendula.data.tasks

import android.net.Uri
import androidx.core.net.toUri

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
        const val RDATE = "rdate"
        const val EXDATE = "exdate"
        /** Set on an override row — the master occurrence this one replaces. */
        const val ORIGINAL_INSTANCE_ID = "original_instance_id"
        /** On an override row: the start of the occurrence it replaces. */
        const val ORIGINAL_INSTANCE_TIME = "original_instance_time"
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

        const val HAS_ALARMS = "has_alarms"
        const val ORIGINAL_INSTANCE_ALLDAY = "original_instance_allday"
        const val LIST_OWNER = "list_owner"
        const val LIST_ACCESS_LEVEL = "list_access_level"
        const val VISIBLE = "visible"
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
        const val INSTANCE_DURATION = "instance_duration"

        /**
         * The occurrence's `RECURRENCE-ID` — the time this occurrence was
         * instantiated at, before any override moved it. Set on every occurrence
         * of a recurring task, which is what makes it the occurrence's identity.
         */
        const val INSTANCE_ORIGINAL_TIME = "instance_original_time"
    }

    /** The `properties` table — per-task side rows, discriminated by [Properties.MIMETYPE]. */
    object Properties {
        const val PATH = "properties"
        const val PROPERTY_ID = "property_id"
        const val TASK_ID = "task_id"
        const val MIMETYPE = "mimetype"
        const val DATA0 = "data0"
        const val DATA1 = "data1"
        const val DATA2 = "data2"
        const val DATA3 = "data3"
    }

    /**
     * An alarm property row — a per-task reminder lead.
     *
     * Storage and sync format *only*: the provider fires nothing (its alarm
     * scheduling is commented out and the internal `alarms` table is never
     * populated), so [de.jeanlucmakiola.agendula.data.reminders.ReminderScheduler]
     * still arms the real AlarmManager alarm. Writing it here is what makes the
     * lead survive a sync and show up in other OpenTasks clients.
     *
     * The columns are the generic `dataN` slots; the meanings below are the
     * Alarm property's contract for them.
     */
    object Alarm {
        const val MIMETYPE = "vnd.android.cursor.item/alarm"

        /** `data0` — minutes from the reference date; positive means *before* it. */
        const val MINUTES_BEFORE = "data0"

        /** `data1` — which date to count from. */
        const val REFERENCE = "data1"

        /** `data2` — optional message shown with the alarm. */
        const val MESSAGE = "data2"

        /** `data3` — alarm kind. Must be present, and non-zero to count as an alarm. */
        const val ALARM_TYPE = "data3"

        const val REFERENCE_DUE = 1
        const val REFERENCE_START = 2

        /** 0 (NOTHING) is excluded from the provider's `has_alarms` count — use MESSAGE. */
        const val TYPE_MESSAGE = 1
    }

    /**
     * A category property row — one tag. DAVx5 maps these to and from
     * `CATEGORIES`. Inserting with only [NAME] is enough: the provider looks the
     * category up in the task's account, or creates it, and fills in `data0`.
     */
    object Category {
        const val MIMETYPE = "vnd.android.cursor.item/category"

        /** `data1` — the tag as spelled. */
        const val NAME = "data1"

        /** `data2` — the tag's colour, if any. */
        const val COLOR = "data2"
    }

    fun propertiesUri(authority: String): Uri = "content://$authority/${Properties.PATH}".toUri()

    // --- status values (TaskColumns.STATUS_*) --------------------------------
    const val STATUS_NEEDS_ACTION = 0
    const val STATUS_IN_PROCESS = 1
    const val STATUS_COMPLETED = 2
    const val STATUS_CANCELLED = 3

    /** Priority 0 means "no priority"; 1 is highest, 9 lowest (iCalendar). */
    const val PRIORITY_NONE = 0

    fun authorityUri(authority: String): Uri = "content://$authority".toUri()
    fun listsUri(authority: String): Uri = "content://$authority/${Lists.PATH}".toUri()
    fun listUri(authority: String, listId: Long): Uri =
        "content://$authority/${Lists.PATH}/$listId".toUri()
    fun tasksUri(authority: String): Uri = "content://$authority/${Tasks.PATH}".toUri()
    fun instancesUri(authority: String): Uri = "content://$authority/${Instances.PATH}".toUri()

    /**
     * A single occurrence. Updating through this URI is how a *recurring* task is
     * edited: the provider clones the row into an override task
     * (`original_instance_id` set, recurrence fields stripped) instead of moving
     * the series anchor, which is what writing to `tasks/<id>` would do.
     */
    fun instanceUri(authority: String, instanceId: Long): Uri =
        "content://$authority/${Instances.PATH}/$instanceId".toUri()

    /** Append the sync-adapter params required to write local-account rows. */
    fun asSyncAdapter(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(PARAM_ACCOUNT_NAME, accountName)
            .appendQueryParameter(PARAM_ACCOUNT_TYPE, accountType)
            .build()
}
