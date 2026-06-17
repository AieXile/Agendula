package de.jeanlucmakiola.floret.data.tasks

/** No tasks provider (OpenTasks / tasks.org) is installed on the device. */
class ProviderUnavailableException :
    IllegalStateException("No tasks provider installed")

/** A ContentResolver write returned no URI or affected no rows. */
class TaskWriteFailedException(operation: String) :
    RuntimeException("Task write failed: $operation")
