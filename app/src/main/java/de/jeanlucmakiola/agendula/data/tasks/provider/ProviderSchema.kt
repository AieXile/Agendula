package de.jeanlucmakiola.agendula.data.tasks.provider

import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Properties
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks

/** One table [AgendulaTaskProvider] serves, with its columns in cursor order. */
enum class ProviderTable(val path: String, val columns: List<Pair<String, String>>) {
    LISTS(
        Lists.PATH,
        listOf(
            Lists.ID to "INTEGER PRIMARY KEY",
            Lists.NAME to "TEXT",
            Lists.COLOR to "INTEGER",
            Lists.ACCESS_LEVEL to "INTEGER",
            Lists.VISIBLE to "INTEGER",
            Lists.SYNC_ENABLED to "INTEGER",
            Lists.OWNER to "TEXT",
            Lists.ACCOUNT_NAME to "TEXT",
            Lists.ACCOUNT_TYPE to "TEXT",
        ),
    ),
    TASKS(Tasks.PATH, listOf(Tasks.ID to "INTEGER PRIMARY KEY") + TASK_COLUMNS),
    INSTANCES(
        Instances.PATH,
        listOf(
            Tasks.ID to "INTEGER PRIMARY KEY",
            Instances.TASK_ID to "INTEGER",
            Instances.INSTANCE_START to "INTEGER",
            Instances.INSTANCE_START_SORTING to "INTEGER",
            Instances.INSTANCE_DUE to "INTEGER",
            Instances.INSTANCE_DUE_SORTING to "INTEGER",
            Instances.INSTANCE_DURATION to "INTEGER",
            Instances.INSTANCE_ORIGINAL_TIME to "INTEGER",
            Instances.DISTANCE_FROM_CURRENT to "INTEGER",
            Instances.IS_RECURRING to "INTEGER",
        ) + TASK_COLUMNS,
    ),
    PROPERTIES(
        Properties.PATH,
        listOf(
            Properties.PROPERTY_ID to "INTEGER PRIMARY KEY",
            Properties.TASK_ID to "INTEGER",
            Properties.MIMETYPE to "TEXT",
            Properties.DATA0 to "TEXT",
            Properties.DATA1 to "TEXT",
            Properties.DATA2 to "TEXT",
            Properties.DATA3 to "TEXT",
        ),
    ),
    ;

    val idColumn: String get() = columns.first().first

    /** [selection] narrowed to the row [id] names, for a `/<id>` URI. */
    fun selectionFor(id: Long?, selection: String?): String? =
        if (id == null) selection else listOfNotNull("$idColumn = $id", selection?.let { "($it)" }).joinToString(" AND ")

    /** OpenTasks' MIME type for a whole table, or for one row of it. */
    fun mimeType(single: Boolean): String =
        "${if (single) "vnd.android.cursor.item" else "vnd.android.cursor.dir"}/org.dmfs.tasks.$path"
}

/** Every `tasks` column but `_id`; the instances view carries them too. */
private val TASK_COLUMNS: List<Pair<String, String>>
    get() = listOf(
        Tasks.LIST_ID to "INTEGER",
        Tasks.LIST_NAME to "TEXT",
        Tasks.LIST_COLOR to "INTEGER",
        Tasks.LIST_OWNER to "TEXT",
        Tasks.LIST_ACCESS_LEVEL to "INTEGER",
        Tasks.VISIBLE to "INTEGER",
        Tasks.ACCOUNT_NAME to "TEXT",
        Tasks.ACCOUNT_TYPE to "TEXT",
        Tasks.UID to "TEXT",
        Tasks.DELETED to "INTEGER",
        Tasks.TITLE to "TEXT",
        Tasks.DESCRIPTION to "TEXT",
        Tasks.LOCATION to "TEXT",
        Tasks.URL to "TEXT",
        Tasks.PRIORITY to "INTEGER",
        Tasks.CLASSIFICATION to "INTEGER",
        Tasks.STATUS to "INTEGER",
        Tasks.PERCENT_COMPLETE to "INTEGER",
        Tasks.COMPLETED to "INTEGER",
        Tasks.IS_CLOSED to "INTEGER",
        Tasks.IS_NEW to "INTEGER",
        Tasks.TASK_COLOR to "INTEGER",
        Tasks.DTSTART to "INTEGER",
        Tasks.DUE to "INTEGER",
        Tasks.DURATION to "TEXT",
        Tasks.IS_ALLDAY to "INTEGER",
        Tasks.TZ to "TEXT",
        Tasks.RRULE to "TEXT",
        Tasks.RDATE to "TEXT",
        Tasks.EXDATE to "TEXT",
        Tasks.ORIGINAL_INSTANCE_ID to "INTEGER",
        Tasks.ORIGINAL_INSTANCE_TIME to "INTEGER",
        Tasks.ORIGINAL_INSTANCE_ALLDAY to "INTEGER",
        Tasks.PARENT_ID to "INTEGER",
        Tasks.SORTING to "INTEGER",
        Tasks.HAS_ALARMS to "INTEGER",
        Tasks.CREATED to "INTEGER",
        Tasks.LAST_MODIFIED to "INTEGER",
    ) + UNSUPPORTED_TASK_COLUMNS.map { it to "TEXT" }

/**
 * OpenTasks columns Agendula has nothing for. Published as `NULL` so a client
 * that projects them reads nothing rather than failing the query.
 */
private val UNSUPPORTED_TASK_COLUMNS = listOf(
    "geo", "organizer", "pinned", "has_properties", "completed_is_allday", "original_instance_sync_id",
    "version", "_dirty", "_sync_id", "sync_version",
    "sync1", "sync2", "sync3", "sync4", "sync5", "sync6", "sync7", "sync8",
)
