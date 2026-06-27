package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks

/** Column lists requested from the provider. Order is irrelevant; we read by name. */
object TaskProjections {

    val LISTS: Array<String> = arrayOf(
        Lists.ID,
        Lists.NAME,
        Lists.COLOR,
        Lists.VISIBLE,
        Lists.SYNC_ENABLED,
        Lists.OWNER,
        Lists.ACCOUNT_NAME,
        Lists.ACCOUNT_TYPE,
    )

    /** Read from the `instances` view (inherits all task columns). */
    val INSTANCES: Array<String> = arrayOf(
        Tasks.ID,
        Instances.TASK_ID,
        Tasks.LIST_ID,
        Tasks.TITLE,
        Tasks.DESCRIPTION,
        Tasks.LOCATION,
        Tasks.URL,
        Tasks.PRIORITY,
        Tasks.STATUS,
        Tasks.PERCENT_COMPLETE,
        Tasks.COMPLETED,
        Tasks.IS_ALLDAY,
        Tasks.TZ,
        Instances.INSTANCE_START,
        Instances.INSTANCE_DUE,
        Tasks.TASK_COLOR,
        Tasks.LIST_COLOR,
        Tasks.LIST_NAME,
        Tasks.ACCOUNT_NAME,
        Tasks.PARENT_ID,
        Instances.IS_RECURRING,
        Instances.DISTANCE_FROM_CURRENT,
        Tasks.CREATED,
        Tasks.LAST_MODIFIED,
    )
}
