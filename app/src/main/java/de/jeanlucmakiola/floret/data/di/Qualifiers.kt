package de.jeanlucmakiola.floret.data.di

import javax.inject.Qualifier

/** Marks the IO [kotlinx.coroutines.CoroutineDispatcher] for provider access. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
