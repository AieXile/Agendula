package com.aiexile.agendula.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Runs [block] on every `ON_RESUME`.
 *
 * For state the app cannot observe because it is granted, revoked or installed
 * outside it — a runtime permission, an exact-alarm allowance, a provider app —
 * which otherwise stays stale until the process restarts.
 */
@Composable
fun OnResume(block: () -> Unit) {
    val current by rememberUpdatedState(block)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
