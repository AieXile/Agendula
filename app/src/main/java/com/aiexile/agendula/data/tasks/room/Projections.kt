package com.aiexile.agendula.data.tasks.room

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * A list plus its account's display name, which the domain
 * [com.aiexile.agendula.domain.TaskList] carries and groups by.
 * `null` means a device-only list.
 */
data class TaskListRow(
    @Embedded val list: TaskListEntity,
    @ColumnInfo(name = "account_display_name") val accountDisplayName: String?,
)

/**
 * A task plus the columns of its list the domain
 * [com.aiexile.agendula.domain.Task] carries, so reading a screenful is
 * one query rather than one per list.
 */
data class TaskRow(
    @Embedded val task: TaskEntity,
    @ColumnInfo(name = "list_name") val listName: String,
    @ColumnInfo(name = "list_color") val listColor: Int,
    @ColumnInfo(name = "account_display_name") val accountDisplayName: String?,
    @ColumnInfo(name = "list_read_only") val listReadOnly: Boolean = false,
)
