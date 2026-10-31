package com.aiexile.agendula.data.tasks.room

/**
 * Moving a task — a series master and its overrides — to another list.
 *
 * A calendar resource lives in exactly one collection, so a move is a delete in
 * the old one and a create in the new one. Keeping the href would PUT the edit
 * back into the collection the task just left.
 *
 * The rows move and keep their ids, so alarms and anything holding the task id
 * stay put. What the old collection is owed is a **tombstone**: a detached copy
 * of the master, left behind in the old list with the old href and ETag, which
 * the next sync turns into a conditional DELETE like any other.
 *
 * Pure, so it is testable on the JVM; [RoomTasksDataSource] applies it.
 */
object TaskMove {

    data class Plan(
        /** The moved rows, rewritten for the target list. */
        val update: List<TaskEntity>,
        /** Rows that go: a revived tombstone, and overrides already tombstoned. */
        val delete: List<Long>,
        /** The pending DELETE to insert into the source list, if the server has the task. */
        val tombstone: TaskEntity?,
    )

    /**
     * @param edited the master with the form applied; its `listId` is the target.
     * @param previous the master as it was, in the source list.
     * @param overrides every override of [previous], tombstones included.
     * @param sourceSynced whether the source list belongs to an account.
     * @param targetTombstone the target list's pending DELETE for this UID. A task
     *   moved back before the sync ran takes its old resource back instead of
     *   deleting and recreating it.
     */
    fun plan(
        edited: TaskEntity,
        previous: TaskEntity,
        overrides: List<TaskEntity>,
        sourceSynced: Boolean,
        targetTombstone: TaskEntity?,
    ): Plan {
        val revived = targetTombstone?.takeIf { it.isDeleted && it.uid == previous.uid }
        val href = revived?.href
        val etag = revived?.etag
        val (buried, live) = overrides.partition { it.isDeleted }

        val moved = listOf(edited.copy(href = href, etag = etag, isDirty = true)) +
            live.map {
                it.copy(listId = edited.listId, href = href, etag = etag, isDirty = true)
            }

        val tombstone = previous.takeIf { sourceSynced && it.href != null }?.copy(
            id = 0,
            masterId = null,
            parentId = null,
            isDeleted = true,
            isDirty = true,
        )

        return Plan(
            update = moved,
            delete = buried.map { it.id } + listOfNotNull(revived?.id),
            tombstone = tombstone,
        )
    }
}
