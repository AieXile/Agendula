package de.jeanlucmakiola.agendula.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.jeanlucmakiola.agendula.data.sync.AccountCreator
import de.jeanlucmakiola.agendula.data.sync.AccountRepository
import de.jeanlucmakiola.agendula.data.sync.CalDavGateway
import de.jeanlucmakiola.agendula.data.sync.LoginFlowRecord
import de.jeanlucmakiola.agendula.data.sync.PendingLoginFlowStore
import de.jeanlucmakiola.agendula.data.sync.SyncOnEdit
import de.jeanlucmakiola.agendula.data.sync.OkHttpCalDavGateway
import de.jeanlucmakiola.agendula.data.tasks.AndroidProviderEnvironment
import de.jeanlucmakiola.agendula.data.tasks.AndroidTasksDataSource
import de.jeanlucmakiola.agendula.data.tasks.ModeRoutingTasksDataSource
import de.jeanlucmakiola.agendula.data.tasks.ProviderEnvironment
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.TasksDataSource
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.TasksRepositoryImpl
import de.jeanlucmakiola.agendula.data.tasks.room.LocalWriteListener
import de.jeanlucmakiola.agendula.data.tasks.room.RoomTasksDataSource
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Provider
import javax.inject.Singleton

/**
 * ⚠️ Every one of these needs a corruption handler, and without one a truncated
 * `preferences_pb` is a **crash at every launch**: `DataStore.data` throws
 * `CorruptionException` on collection, and the collectors here are root
 * coroutines in a scope with no handler. A file half-written by a kill during
 * `edit` is the ordinary way to get one.
 *
 * Starting empty is the only recovery available and it is a mild one: the
 * settings store falls back to defaults, the sync-state store to "never
 * reconciled", and the credential store to accounts that ask to be signed in
 * again — all states the app already knows how to be in, unlike a launch loop.
 */
private fun replaceCorrupted() = ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.agendulaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "agendula_prefs",
    corruptionHandler = replaceCorrupted(),
)

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

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {

    @Binds
    @Singleton
    abstract fun bindTasksRepository(impl: TasksRepositoryImpl): TasksRepository

    @Binds
    @Singleton
    abstract fun bindProviderEnvironment(impl: AndroidProviderEnvironment): ProviderEnvironment

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

    // Deliberately unqualified-free of the routing above: this is the external
    // store itself, for the one caller that has to read it while another store is
    // the active one.
    @Binds
    @Singleton
    @ExternalStore
    abstract fun bindExternalTasksDataSource(impl: AndroidTasksDataSource): TasksDataSource
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.agendulaDataStore

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

    @Provides
    @Singleton
    fun provideTasksDatabase(@ApplicationContext context: Context): TasksDatabase =
        Room.databaseBuilder(context, TasksDatabase::class.java, TasksDatabase.NAME)
            // Room's default, stated rather than assumed: Auto Backup copies files
            // without checkpointing, so a `-wal` sidecar can hold writes the
            // backed-up `.db` does not. The backup rules carry all three files and
            // the app checkpoints on ON_STOP.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    /**
     * The active store, chosen by [StorageMode].
     *
     * Resolved per injection point rather than bound once, because the mode is a
     * user setting that [de.jeanlucmakiola.agendula.data.tasks.StorageModeHolder]
     * can change while the process lives. Both implementations are singletons, so
     * this picks between two long-lived objects rather than building either.
     */
    @Provides
    @Singleton
    fun provideTasksDataSource(
        resolver: ProviderResolver,
        room: Provider<RoomTasksDataSource>,
        external: Provider<AndroidTasksDataSource>,
    ): TasksDataSource = ModeRoutingTasksDataSource(resolver, room, external)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        // SupervisorJob so one failing collector can't take the others down with it.
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
