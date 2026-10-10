package de.jeanlucmakiola.agendula.data.sync

/** Asks for an account to be synced now. A no-op in the `offline` flavor. */
fun interface SyncRequests {
    fun syncNow(accountName: String)
}
