package com.aiexile.agendula.data.tasks.room

/**
 * Told which lists a local task write touched, so an account-backed one can be
 * pushed without waiting for the four-hour schedule.
 *
 * Called on the writing thread, after the write. Implementations must be cheap
 * and must not throw.
 */
fun interface LocalWriteListener {

    fun onWritten(listIds: Set<Long>)

    companion object {
        val NONE = LocalWriteListener { }
    }
}
