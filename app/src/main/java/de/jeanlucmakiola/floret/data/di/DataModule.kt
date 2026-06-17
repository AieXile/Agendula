package de.jeanlucmakiola.floret.data.di

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
import de.jeanlucmakiola.floret.data.tasks.AndroidTasksDataSource
import de.jeanlucmakiola.floret.data.tasks.TasksDataSource
import de.jeanlucmakiola.floret.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.data.tasks.TasksRepositoryImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

private val Context.floretDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "floret_prefs",
)

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {

    @Binds
    @Singleton
    abstract fun bindTasksDataSource(impl: AndroidTasksDataSource): TasksDataSource

    @Binds
    @Singleton
    abstract fun bindTasksRepository(impl: TasksRepositoryImpl): TasksRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.floretDataStore

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
