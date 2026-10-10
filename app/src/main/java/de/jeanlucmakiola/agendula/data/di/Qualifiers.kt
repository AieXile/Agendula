package de.jeanlucmakiola.agendula.data.di

import javax.inject.Qualifier

/** Marks the IO [kotlinx.coroutines.CoroutineDispatcher] for provider access. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Marks the process-lifetime [kotlinx.coroutines.CoroutineScope] — for work that
 * outlives any screen and has nothing to be cancelled by, such as keeping the
 * selected storage mode mirrored out of DataStore. It is never cancelled, so
 * don't launch anything unbounded in it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Marks the **external** provider's [de.jeanlucmakiola.agendula.data.tasks
 * .TasksDataSource] — the OpenTasks/tasks.org path specifically, rather than
 * whichever store the active mode selects.
 *
 * Only the one-time copy into our own store needs to name a store this way;
 * everything else goes through the routed source and must keep doing so. Having
 * it as a binding rather than depending on the concrete class is also what lets
 * that copy be tested against a fake.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExternalStore
