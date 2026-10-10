package de.jeanlucmakiola.agendula.data.di

import javax.inject.Qualifier

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
