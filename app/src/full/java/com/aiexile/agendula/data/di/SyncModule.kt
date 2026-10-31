package com.aiexile.agendula.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.aiexile.agendula.data.sync.AccountCreator
import com.aiexile.agendula.data.sync.AccountRepository
import com.aiexile.agendula.data.sync.CalDavGateway
import com.aiexile.agendula.data.sync.LoginFlowRecord
import com.aiexile.agendula.data.sync.OkHttpCalDavGateway
import com.aiexile.agendula.data.sync.PendingLoginFlowStore
import com.aiexile.agendula.data.sync.RemoteListRepository
import com.aiexile.agendula.data.sync.RemoteLists
import com.aiexile.agendula.data.sync.SyncNoticeNotifier
import com.aiexile.agendula.data.sync.SyncOnEdit
import com.aiexile.agendula.data.sync.SyncRequests
import com.aiexile.agendula.data.sync.SyncTrigger
import com.aiexile.agendula.data.sync.push.PushRegistrar
import com.aiexile.agendula.data.tasks.room.LocalWriteListener
import javax.inject.Provider
import javax.inject.Singleton

/** See [CredentialsDataStore] for why this is a separate file. */
private val Context.credentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = CREDENTIALS_DATASTORE,
    corruptionHandler = replaceCorrupted(),
)

/** See [SyncStateDataStore] for why this is a separate file. */
private val Context.syncStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SYNC_STATE_DATASTORE,
    corruptionHandler = replaceCorrupted(),
)

/**
 * Named here and in `backup_rules.xml` / `data_extraction_rules.xml`, which
 * exclude `datastore/$CREDENTIALS_DATASTORE.preferences_pb` by this name.
 */
const val CREDENTIALS_DATASTORE = "agendula_credentials"

/**
 * Named here and in `backup_rules.xml` / `data_extraction_rules.xml`, which
 * exclude `datastore/$SYNC_STATE_DATASTORE.preferences_pb` by this name.
 */
const val SYNC_STATE_DATASTORE = "agendula_sync_state"

/** CalDAV sync: the `full` flavor's side of the seams `main` declares. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindModule {

    @Binds
    @Singleton
    abstract fun bindCalDavGateway(impl: OkHttpCalDavGateway): CalDavGateway

    @Binds
    @Singleton
    abstract fun bindAccountCreator(impl: AccountRepository): AccountCreator

    @Binds
    @Singleton
    abstract fun bindLoginFlowRecord(impl: PendingLoginFlowStore): LoginFlowRecord

    @Binds
    @Singleton
    abstract fun bindLocalWriteListener(impl: SyncOnEdit): LocalWriteListener

    @Binds
    @Singleton
    abstract fun bindRemoteLists(impl: RemoteListRepository): RemoteLists

    @Binds
    @Singleton
    abstract fun bindSyncRequests(impl: SyncTrigger): SyncRequests

    @Binds
    @IntoSet
    abstract fun bindSyncNoticeChannel(impl: SyncNoticeNotifier): ChannelRefresher
}

@Module
@InstallIn(SingletonComponent::class)
object SyncProvideModule {

    @Provides
    @Singleton
    @CredentialsDataStore
    fun provideCredentialsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.credentialsDataStore

    @Provides
    @Singleton
    @SyncStateDataStore
    fun provideSyncStateDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.syncStateDataStore

    /**
     * Sync hard on app open: the periodic worker's interval is a floor, and in
     * the `rare` and `restricted` App Standby buckets it may not have run at
     * all. `KEEP` makes rescheduling idempotent, so this also repairs a schedule
     * lost to "clear app data" or to a restore.
     */
    @Provides
    @IntoSet
    fun syncOnOpenHook(
        accounts: Provider<AccountRepository>,
        syncTrigger: Provider<SyncTrigger>,
        pendingLoginFlows: Provider<PendingLoginFlowStore>,
        push: Provider<PushRegistrar>,
    ): LaunchHook = LaunchHook {
        runCatching {
            accounts.get().rescheduleAll()
            accounts.get().syncable().forEach { syncTrigger.get().enqueue(it.displayName) }
            // A login flow the previous process died in the middle of.
            // Its password, if the user approved, exists nowhere else.
            pendingLoginFlows.get().reclaim()
        }
        // Last and on its own: it waits on the network, and must not hold up
        // the reclaim above. Re-registering on open is what the connector
        // recommends.
        runCatching { push.get().updateAll() }
    }
}
