package de.jeanlucmakiola.floret.data.tasks

/** No tasks provider (OpenTasks / tasks.org) is installed on the device. */
class ProviderUnavailableException :
    IllegalStateException("No tasks provider installed")

/** A ContentResolver write returned no URI or affected no rows. */
class TaskWriteFailedException(operation: String) :
    RuntimeException("Task write failed: $operation")

/**
 * The task changed (e.g. a DAVx5 sync or another app) since the edit form loaded
 * it, so saving would clobber that change. The UI offers to overwrite or reload.
 */
class TaskConflictException(taskId: Long) :
    RuntimeException("Task $taskId changed since it was loaded")
