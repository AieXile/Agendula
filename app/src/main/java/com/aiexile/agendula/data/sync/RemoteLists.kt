package com.aiexile.agendula.data.sync

import com.aiexile.agendula.data.tasks.room.AccountEntity

/**
 * Task lists that live on a server: making, renaming, recolouring and deleting
 * them. The `full` flavor talks CalDAV; the `offline` one has no accounts, so
 * every list is device-only and none of this is ever reached with a real one.
 */
interface RemoteLists {

    /** Why a collection write did not happen, in a form the UI can translate. */
    sealed interface Outcome {
        data object Done : Outcome

        /** The server said no, and will say no again. */
        data class Refused(val code: Int) : Outcome

        /** The server could not be reached. Worth another try. */
        data object Unreachable : Outcome

        /** This account cannot make collections at all — iCloud, Posteo, Google. */
        data object Unsupported : Outcome

        /** Ours is a read-only share; the write belongs to whoever owns it. */
        data object ReadOnly : Outcome

        /** The account is gone, stopped, or has no credential we can decrypt. */
        data object NoAccount : Outcome

        /**
         * The server answered something this call cannot make sense of.
         *
         * ⚠️ Not [Unreachable]. `CollectionOutcome` is one type across create,
         * update and delete, so each of them has branches the other's method
         * can return and its own cannot — and mapping those to [Unreachable]
         * told someone sitting on wifi that they were offline. Unreachable is a
         * claim about the network, and this is not one.
         */
        data object Unexpected : Outcome
    }

    /** The accounts a new list may be created on. */
    suspend fun creatableAccounts(): List<AccountEntity>

    /** Makes a collection on [accountId]'s home set and a row pointing at it. */
    suspend fun create(accountId: Long, name: String, color: Int): Outcome

    suspend fun rename(listId: Long, name: String, color: Int): Outcome

    suspend fun delete(listId: Long): Outcome
}
