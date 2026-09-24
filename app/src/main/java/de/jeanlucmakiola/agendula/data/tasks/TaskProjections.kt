package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists

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

    // No `instances` projection on purpose: that read passes `projection = null`
    // (all columns), because the view's shape differs across provider versions —
    // tasks.org's bundled OpenTasks has no `is_recurring`, for one. A fixed list
    // here would drift out of sync with the by-name mapper and quietly drop
    // columns it depends on. See AndroidTasksDataSource.queryInstances.
}
