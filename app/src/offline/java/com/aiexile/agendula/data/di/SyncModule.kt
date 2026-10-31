package com.aiexile.agendula.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.aiexile.agendula.data.sync.RemoteLists
import com.aiexile.agendula.data.sync.RemoteLists.Outcome
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.agendula.data.tasks.room.LocalWriteListener
import com.aiexile.agendula.data.sync.SyncRequests

/**
 * The `offline` flavor has no CalDAV, no accounts and no network permission, so
 * every list is device-only and the seams `main` declares are inert.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    fun provideLocalWriteListener(): LocalWriteListener = LocalWriteListener.NONE

    @Provides
    fun provideSyncRequests(): SyncRequests = SyncRequests { }

    @Provides
    fun provideRemoteLists(): RemoteLists = NoRemoteLists
}

private object NoRemoteLists : RemoteLists {
    override suspend fun creatableAccounts(): List<AccountEntity> = emptyList()
    override suspend fun create(accountId: Long, name: String, color: Int): Outcome = Outcome.NoAccount
    override suspend fun rename(listId: Long, name: String, color: Int): Outcome = Outcome.NoAccount
    override suspend fun delete(listId: Long): Outcome = Outcome.NoAccount
}
