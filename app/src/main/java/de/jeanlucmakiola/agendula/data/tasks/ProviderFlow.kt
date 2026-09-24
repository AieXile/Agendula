package de.jeanlucmakiola.agendula.data.tasks

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

private const val BASE_RETRY_MS = 1_000L
private const val MAX_RETRY_MS = 30_000L

/** 1s, 2s, 4s … capped at 30s, so a permanently-absent provider costs little. */
private fun retryDelayMs(attempt: Long): Long =
    (BASE_RETRY_MS shl attempt.coerceAtMost(5).toInt()).coerceAtMost(MAX_RETRY_MS)

/**
 * Recover a provider-backed flow without killing it.
 *
 * Provider reads fail for reasons that resolve on their own: the read permission
 * isn't granted yet (first launch collects before the permission gate), or the
 * provider app is mid-update. A terminal `catch` swallows the failure *and*
 * cancels the upstream, so the flow never produces again — the screen stays empty
 * until the process restarts, even after the user grants the permission.
 *
 * This emits [fallback] instead and keeps retrying with a capped backoff, so the
 * collector recovers on its own once the provider becomes readable.
 */
fun <T> Flow<T>.recoveringFromProviderFailure(fallback: () -> T): Flow<T> =
    retryWhen { _, attempt ->
        emit(fallback())
        delay(retryDelayMs(attempt))
        true
    }
