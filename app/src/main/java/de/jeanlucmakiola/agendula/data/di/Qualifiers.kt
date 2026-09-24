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
 * Marks the DataStore holding **only** the Keystore-encrypted app passwords.
 *
 * A separate file from `agendula_prefs` on purpose: Auto Backup includes
 * `datastore/`, and a restored ciphertext is permanently undecryptable because
 * Keystore keys are non-exportable. Its own file is what lets the backup rules
 * exclude the credentials and nothing else — excluding the whole database or
 * all of DataStore would trade a latent bug for a live one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CredentialsDataStore

/**
 * Marks the DataStore holding per-device **sync bookkeeping** — the quarantine
 * counters and the full-reconciliation clock.
 *
 * Its own file for the same reason the credentials have one: Auto Backup
 * includes `datastore/`, and every value in here is a statement about *this*
 * device's conversation with a server. Restored onto a new install they are all
 * lies, and two of them are dangerous — a restored "reconciled recently" makes
 * the engine trust a sync token for another day, which is precisely the silently
 * pruned change log the full path exists to catch, and a restored quarantine
 * count silently skips resources that were never tried here.
 *
 * Not user data, so nothing is lost by excluding it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SyncStateDataStore

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
