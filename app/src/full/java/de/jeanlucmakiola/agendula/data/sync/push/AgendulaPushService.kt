package de.jeanlucmakiola.agendula.data.sync.push

import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/**
 * Where the UnifiedPush distributor reaches us; the instance is the account id.
 * Work runs in the application scope, since the connector unbinds after a second.
 */
@AndroidEntryPoint
class AgendulaPushService : PushService() {

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @Inject lateinit var registrar: PushRegistrar

    @Inject lateinit var messages: PushMessageHandler

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val accountId = instance.toLongOrNull() ?: return
        scope.launch { runCatching { registrar.onNewEndpoint(accountId, endpoint) }.onFailure(::log) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // Encryption is mandatory in the draft.
        if (!message.decrypted) {
            Log.w(TAG, "dropped a push message that did not decrypt")
            return
        }
        val content = message.content.toString(Charsets.UTF_8)
        scope.launch { runCatching { messages.handle(content, instance) }.onFailure(::log) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "distributor refused registration for $instance: $reason")
        // A transient failure leaves the last endpoint valid; the next renewal retries.
        if (reason == FailedReason.NETWORK || reason == FailedReason.INTERNAL_ERROR) return
        val accountId = instance.toLongOrNull() ?: return
        scope.launch { runCatching { registrar.onUnregistered(accountId) }.onFailure(::log) }
    }

    override fun onUnregistered(instance: String) {
        val accountId = instance.toLongOrNull() ?: return
        scope.launch { runCatching { registrar.onUnregistered(accountId) }.onFailure(::log) }
    }

    private fun log(error: Throwable) = Log.w(TAG, "push handling failed", error)

    private companion object {
        const val TAG = "AgendulaPushService"
    }
}
