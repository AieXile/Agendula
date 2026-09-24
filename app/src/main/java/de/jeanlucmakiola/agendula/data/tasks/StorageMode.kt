package de.jeanlucmakiola.agendula.data.tasks

/**
 * Which task store backs the app — the user's choice.
 *
 * Only two values.
 * **Synced is not a third store**: it is [OWN] with an account attached to a
 * list, which is derived state rather than something the user picks. Attaching
 * one is a plain `UPDATE task_lists SET account_id = ?` — not the full data
 * migration it was under the dmfs provider, whose `ACCOUNT_TYPE` was write-once.
 */
enum class StorageMode {
    /**
     * Agendula's own Room database. The default, and always available: there is
     * no authority, no ContentResolver and no permission to grant.
     */
    OWN,

    /**
     * A tasks provider app already on the device (OpenTasks, tasks.org), synced by
     * whatever that provider's engine is — DAVx5 and friends. Still fully
     * supported, now a choice rather than the only way. Requires that provider's
     * runtime read/write permissions.
     */
    EXTERNAL,
}
