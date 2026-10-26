package de.jeanlucmakiola.agendula.ui.accounts.add

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.sync.AccountCreator
import de.jeanlucmakiola.agendula.data.sync.AccountRepository
import de.jeanlucmakiola.agendula.data.sync.CalDavGateway
import de.jeanlucmakiola.agendula.data.sync.LoginFlowRecord
import de.jeanlucmakiola.caldav.CalDavDiscovery
import de.jeanlucmakiola.caldav.CalDavProvider
import de.jeanlucmakiola.caldav.NextcloudLoginFlow
import de.jeanlucmakiola.caldav.ServerQuirk
import de.jeanlucmakiola.caldav.ServiceDiscovery
import de.jeanlucmakiola.caldav.TaskCollection
import de.jeanlucmakiola.agendula.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/**
 * Which service the account lives on.
 *
 * Picked before anything is typed, because it is the only thing that makes the
 * next question answerable: a hosted service is reached by the email address the
 * user already knows, while a server they run is reached by a URL only they have.
 * Asking for "an address" without knowing which of the two it is was the flow's
 * first dead end.
 */
sealed interface ProviderChoice {

    /** The service itself, or null for a server we know nothing about yet. */
    val provider: CalDavProvider?

    /** A service we know by name, and therefore by domain, quirk and sign-in route. */
    data class Service(override val provider: CalDavProvider) : ProviderChoice

    /** Anything else that speaks CalDAV. Identified after discovery, if at all. */
    data object OtherServer : ProviderChoice {
        override val provider: CalDavProvider? get() = null
    }
}

/** Where the user is in adding an account. */
sealed interface AddAccountStep {

    /** Step 1: pick the service. */
    data class ChooseProvider(val choice: ProviderChoice? = null) : AddAccountStep

    /**
     * Step 2, first half: what to go and do before any of this will work.
     *
     * Its own screen rather than a note under the address field, because it is
     * an errand in someone else's web app — the user leaves, does four things,
     * and comes back — and an instruction you are meant to act on before typing
     * does not belong underneath the thing you type into.
     */
    data class PrepareAccess(
        val choice: ProviderChoice,
        val quirk: ServerQuirk,
    ) : AddAccountStep

    /** Step 2, second half: type the one address the choice above asks for. */
    data class EnterAddress(
        val choice: ProviderChoice,
        val input: String = "",
        /** A cause, not a message — the screen owns the wording. */
        val error: CalDavDiscovery.Outcome.Cause? = null,
        /** The email address found no server, so a hosted choice asks for its URL instead. */
        val manualServer: Boolean = false,
    ) : AddAccountStep {
        /** Whether the field takes an email address rather than a server URL. */
        val asksForEmail: Boolean get() = choice.provider?.hosted == true && !manualServer
    }

    data class Working(val message: AddAccountMessage.Progress) : AddAccountStep

    /** The server wants credentials and is not a Nextcloud we can hand to a browser. */
    data class EnterCredentials(
        val username: String = "",
        val password: String = "",
        val error: AddAccountMessage? = null,
    ) : AddAccountStep

    /**
     * The flow has a login URL, and something about it has to be read *before*
     * the browser opens.
     *
     * ⚠️ Its own step, not a note on [WaitingForBrowser]. The note and the
     * launch used to arrive in the same state update, so the Custom Tab was
     * already covering the screen by the time the warning drew — and
     * [NextcloudLoginFlow.Flow]'s own doc claims "the UI confirms it with the
     * user, quoting the cause". That page is where the **account** password is
     * typed, so the confirmation has to come first or it is not a confirmation.
     *
     * Two things get here. A [hostMismatch] means the server sent the sign-in
     * somewhere other than the address that was typed — legitimate behind a
     * reverse proxy, which is why it is confirmed rather than refused. An
     * [insecure] login URL means the password would travel in the clear:
     * `requireSecureOrigin` cannot fire for it, because a typed `http://` base
     * makes a cleartext login URL consistent rather than a downgrade.
     */
    data class ConfirmBrowser(
        val hostMismatch: NextcloudLoginFlow.HostMismatch? = null,
        val insecure: Boolean = false,
    ) : AddAccountStep

    /**
     * The browser has the flow. We poll until the user approves, and offer an
     * explicit way out — Custom Tabs return **no result** when dismissed, and
     * Nextcloud's flow ends on a "you can close this window" page that never
     * comes back to the app.
     */
    data class WaitingForBrowser(
        val hostMismatch: NextcloudLoginFlow.HostMismatch? = null,
        val error: AddAccountMessage? = null,
    ) : AddAccountStep

    data class ChooseLists(
        val collections: List<TaskCollection>,
        val selected: Set<HttpUrl>,
    ) : AddAccountStep

    /**
     * What the flow just built, before it hands back.
     *
     * The account is already saved by the time this draws — it is a receipt, not
     * a confirmation. It exists because the wizard used to vanish on success,
     * leaving no answer to "which lists did it take, and under what name?" short
     * of going and finding the account again.
     */
    data class Summary(
        val provider: CalDavProvider?,
        val title: String,
        val secondary: String?,
        val username: String,
        val lists: List<String>,
    ) : AddAccountStep

    data object Done : AddAccountStep
}

data class AddAccountUiState(
    val step: AddAccountStep = AddAccountStep.ChooseProvider(),
    /**
     * A warning the user should read before going further — the three providers
     * whose real failure is not "wrong password", and a server that sent the
     * login flow to a different host than the one typed.
     */
    val quirk: ServerQuirk? = null,
    /**
     * The login flow named an origin we could not have reached, and we used the
     * one that answered instead.
     *
     * Sticky, unlike the per-step note: it is learned during the browser step
     * but the setting it blames is what the user has to go and fix, so it has to
     * survive into the steps after it.
     */
    val originMismatch: NextcloudLoginFlow.HostMismatch? = null,
    /**
     * Whether this run includes the errand step, which not every service needs.
     *
     * The flow's length is therefore a property of the *choice*, not a constant:
     * a service with an app password to mint is one screen longer than one
     * without, and a counter that says "of 4" through a five-screen flow is
     * simply wrong.
     */
    val hasSetupStep: Boolean = false,
    /** Set when the flow cannot continue at all; the UI offers only "start over". */
    val fatal: AddAccountMessage? = null,
    /** Non-null once the browser flow has a URL to open. */
    val openInBrowser: HttpUrl? = null,
    /** Signing an existing account in again, rather than adding one. */
    val reauthenticating: Boolean = false,
) {
    /** How many numbered steps this run has, which the errand can push to five. */
    val totalSteps: Int get() = if (hasSetupStep) ADD_ACCOUNT_STEPS + 1 else ADD_ACCOUNT_STEPS
}

/** The wizard's own visible steps, before a service adds an errand to them. */
const val ADD_ACCOUNT_STEPS = 4

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val repository: AccountCreator,
    private val gateway: CalDavGateway,
    private val pendingFlow: LoginFlowRecord,
    /**
     * Where the wizard's own state lives, so process death is not the end of it.
     *
     * ⚠️ Every field below used to be a plain `var`, and the browser step makes
     * process death ordinary rather than exotic: the Custom Tab is a separate
     * task, so ours is a background process while the user approves, and the
     * system kills those. [PendingLoginFlowStore] closed the half of that window
     * *before* approval — a poll token can be reclaimed and the password revoked.
     * This closes the half after it, where an already-minted one-shot password
     * existed only in memory.
     *
     * ⚠️ What goes in here is deliberately not "everything". A password the user
     * **typed** is not saved: it is their account password on the generic CalDAV
     * route, retyping it costs one field, and saved state is written out by the
     * system. A password the server **minted** is saved, because it is a
     * device-scoped app password that cannot be re-obtained — the 200 that
     * carried it is spent — and losing it leaves a live credential in the user's
     * device list under the same name as every other attempt, which is exactly
     * what they cannot tell apart and so dare not prune. Saved state dies with
     * the task, so unlike a store of our own it cannot outlive the flow.
     */
    private val handle: SavedStateHandle,
    // ⚠️ Not viewModelScope. Handing the password back has to survive the
    // ViewModel that minted it, and androidx closes viewModelScope *before*
    // onCleared runs, so a launch there never executes its body.
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AddAccountUiState())
    val state: StateFlow<AddAccountUiState> = mutableState.asStateFlow()

    /**
     * The one write path for [state], so that [record] cannot be forgotten at
     * one of the twenty-odd places the flow changes step.
     *
     * ⚠️ Synchronous, not a collector on [state]. The system asks for saved
     * state on the main thread, and a recorder that only runs when the
     * dispatcher next gets a turn is a recorder that loses whatever happened
     * immediately before the kill — which is precisely the moment worth saving.
     */
    private var current: AddAccountUiState
        get() = mutableState.value
        set(value) {
            mutableState.value = value
            record(value)
        }

    private fun updateState(transform: (AddAccountUiState) -> AddAccountUiState) {
        current = transform(current)
    }

    private var choice: ProviderChoice?
        get() = handle.get<String>(KEY_CHOICE)?.let(::choiceOf)
        set(value) {
            handle[KEY_CHOICE] = value?.let {
                when (it) {
                    is ProviderChoice.Service -> it.provider.name
                    ProviderChoice.OtherServer -> OTHER_SERVER_TOKEN
                }
            }
        }

    /**
     * What is in the address field, as opposed to [typedInput], which is what was
     * last *submitted*. Kept so stepping back to the picker and forward again
     * returns to a filled field rather than an empty one.
     */
    private var addressInput: String
        get() = handle[KEY_ADDRESS_INPUT] ?: ""
        set(value) { handle[KEY_ADDRESS_INPUT] = value }

    private var typedInput: String
        get() = handle[KEY_TYPED_INPUT] ?: ""
        set(value) { handle[KEY_TYPED_INPUT] = value }

    private var serverRoot: HttpUrl?
        get() = handle.get<String>(KEY_SERVER_ROOT)?.toHttpUrlOrNull()
        set(value) { handle[KEY_SERVER_ROOT] = value?.toString() }

    private var username: String
        get() = handle[KEY_USERNAME] ?: ""
        set(value) { handle[KEY_USERNAME] = value }

    /** The account being signed in again, or null when adding a new one. */
    private var reauthAccountId: Long?
        get() = handle[KEY_REAUTH_ACCOUNT]
        set(value) { handle[KEY_REAUTH_ACCOUNT] = value }

    /** The email that found no server; Back returns to it from the server field. */
    private var fallbackEmail: String?
        get() = handle[KEY_FALLBACK_EMAIL]
        set(value) { handle[KEY_FALLBACK_EMAIL] = value }

    /** The username the credentials step starts with on a re-authentication. */
    private var suggestedUsername: String
        get() = handle[KEY_SUGGESTED_USERNAME] ?: ""
        set(value) { handle[KEY_SUGGESTED_USERNAME] = value }

    /**
     * ⚠️ Not saved, unlike everything around it. On the browser route this holds
     * a minted app password, which [minted] saves for its own reasons; on the
     * generic route it holds the password the user typed, and that is theirs
     * rather than ours to write down. A restore that has a minted credential
     * refills this from it, and one that does not lands on a field to type into.
     */
    private var appPassword: String = ""

    /**
     * ⚠️ Deliberately **not** saved, and re-derived instead. It carries a
     * principal, several home sets and every collection the server listed, none
     * of which is small and all of which the server will answer for again; a
     * restore that needs it re-runs discovery with the credential we still hold.
     * That also re-reads an ACL or a display name the server changed meanwhile,
     * which a serialised snapshot could not.
     */
    private var found: CalDavDiscovery.Outcome.Found? = null
    private var pollJob: Job? = null

    /**
     * A started flow waiting on the user's say-so, held for the length of
     * [AddAccountStep.ConfirmBrowser].
     *
     * Nothing has been written down for it and nothing has been minted, so
     * dropping it is a complete exit: the server keeps a row nobody will ever
     * approve, and it expires on its own in twenty minutes.
     */
    private var unconfirmedFlow: NextcloudLoginFlow.Flow? = null

    /** The flow the browser was last sent to, for a retry while it is still open. */
    private var browserFlow: NextcloudLoginFlow.Flow? = null

    /** Which hosts asked for credentials, for the cross-domain diagnostic. */
    private var hostsNeedingAuth: List<String>
        get() = handle.get<ArrayList<String>>(KEY_HOSTS_NEEDING_AUTH).orEmpty()
        set(value) { handle[KEY_HOSTS_NEEDING_AUTH] = ArrayList(value) }

    /**
     * A password the browser flow minted and nothing owns yet.
     *
     * Nextcloud returns it exactly once, so if we walk away without either
     * saving or revoking it, it stays valid in the user's device list for ever —
     * under the same name as every other attempt, so they cannot tell which one
     * their working account uses and dare not prune any. Cleared without
     * revoking only when [AccountCreator] takes ownership.
     *
     * ⚠️ The one secret that *is* saved, and the reason [handle] exists at all —
     * see its doc for why this and not the typed password.
     */
    private var minted: CalDavGateway.Credentials?
        get() {
            val password = handle.get<String>(KEY_MINTED_PASSWORD) ?: return null
            val origin = handle.get<String>(KEY_MINTED_ORIGIN)?.toHttpUrlOrNull() ?: return null
            return CalDavGateway.Credentials(
                username = handle[KEY_MINTED_USERNAME] ?: "",
                password = password,
                origin = origin,
            )
        }
        set(value) {
            handle[KEY_MINTED_USERNAME] = value?.username
            handle[KEY_MINTED_PASSWORD] = value?.password
            handle[KEY_MINTED_ORIGIN] = value?.origin?.toString()
        }

    init {
        restore()
    }

    /**
     * Step 1 → step 2, on the tap itself.
     *
     * ⚠️ No Continue. A picker whose rows already say what they are does not
     * need a second confirmation of the same tap — the back arrow is the undo,
     * and it costs one press rather than two.
     *
     * The exception is a service we cannot sync with at all. Google stays on the
     * picker with its reason under it rather than ending the flow: picking the
     * wrong service should cost one tap to correct, not a restart.
     */
    fun onProviderChosen(picked: ProviderChoice) {
        // A different service asks a different question, so the answer to the old
        // one is not an answer to this one.
        if (picked != choice) addressInput = ""
        choice = picked
        clearFallback()
        val quirk = picked.provider?.let(ServerQuirk::forProvider)
        val setup = quirk?.hasSetupSteps == true
        updateState {
            it.copy(
                step = when {
                    quirk?.isFatal == true -> AddAccountStep.ChooseProvider(picked)
                    setup -> AddAccountStep.PrepareAccess(picked, quirk)
                    else -> addressStep(addressInput)
                },
                quirk = quirk,
                // Set on the choice, so the counter is right on the very screen
                // the errand appears on rather than one step later.
                hasSetupStep = setup,
                fatal = null,
            )
        }
    }

    /**
     * Opens the flow on an existing account that needs signing in again: its
     * service, its server and its username filled in, and the save aimed at the
     * same account rather than a new one.
     */
    fun startReauthentication(accountId: Long, principalUrl: String?, username: String?) {
        onStartOver()
        reauthAccountId = accountId
        suggestedUsername = username.orEmpty()
        val principal = principalUrl?.toHttpUrlOrNull()
        val provider = principal?.let(CalDavProvider::forPrincipal)
            ?.takeIf { it in CalDavProvider.selectable }
        onProviderChosen(provider?.let(ProviderChoice::Service) ?: ProviderChoice.OtherServer)
        addressInput = when {
            provider?.hosted == true -> username?.takeIf { '@' in it }.orEmpty()
            principal != null -> serverAddressOf(principal)
            else -> ""
        }
        updateState { state ->
            val step = state.step
            state.copy(
                step = if (step is AddAccountStep.EnterAddress) addressStep(addressInput) else step,
                reauthenticating = true,
            )
        }
    }

    /** The errand is read; on to the address. */
    fun onSetupAcknowledged() {
        if (current.step !is AddAccountStep.PrepareAccess) return
        updateState { it.copy(step = addressStep(addressInput), fatal = null) }
    }

    fun onAddressChanged(value: String) {
        // The fallback matters: a step reached from a failure carries a choice
        // the field would otherwise have no way to read, and without it the
        // field goes dead rather than merely unlabelled.
        val picked = choice ?: ProviderChoice.OtherServer
        choice = picked
        addressInput = value
        updateState {
            it.copy(
                step = addressStep(value),
                // The service's own rule outranks the one the typed host implies:
                // it was chosen deliberately, and it is known before a key is hit.
                quirk = quirkFor(value),
            )
        }
    }

    fun onUsernameChanged(value: String) = updateCredentials { it.copy(username = value) }

    fun onPasswordChanged(value: String) = updateCredentials { it.copy(password = value) }

    /** Step 2 → discovery, unauthenticated. */
    fun onAddressSubmitted() {
        val input = (current.step as? AddAccountStep.EnterAddress)?.input?.trim().orEmpty()
        if (input.isEmpty()) return
        // ⚠️ The retry path, and the one that actually leaks. A post-approval
        // failure sends the user back here with an error, and the screen offers
        // *Continue*, not start over — so a second attempt would mint a second
        // password on top of the first without this.
        discardMintedPassword()
        // ⚠️ And the credential itself, not just the minted one. backToAddress is
        // reachable *after* a successful approval — a post-approval discovery
        // failure lands on an ordinary address step with a live Continue button
        // — and only onStartOver cleared these. So: approve on server A,
        // discovery fails, type server B, B answers anonymously, and onSave sees
        // a non-blank password and creates me@B carrying A's credential.
        username = ""
        appPassword = ""
        // Discovery belongs to the address that produced it, and so does the
        // host list the diagnostics read.
        found = null
        hostsNeedingAuth = emptyList()
        // As does a flow started for it and never opened.
        unconfirmedFlow = null
        // The mismatch is sticky on purpose, but it belongs to the address that
        // produced it — carrying it into a different server's flow makes a claim
        // about that server's settings which was never measured.
        updateState { it.copy(originMismatch = null) }
        typedInput = input
        addressInput = input

        val quirk = ServerQuirk.forInput(input)
        if (quirk?.isFatal == true) {
            // Google supports neither VTODO nor MKCALENDAR. Refusing with an
            // explanation beats a 401 the user cannot act on.
            updateState {
                it.copy(
                    step = addressStep(input),
                    fatal = AddAccountMessage.GoogleUnsupported,
                    quirk = quirk,
                )
            }
            return
        }

        serverRoot = ServiceDiscovery.serverRootFor(input)

        working(AddAccountMessage.Progress.Discovering)
        viewModelScope.launch {
            when (val outcome = gateway.discover(typedInput)) {
                is CalDavDiscovery.Outcome.Found -> onDiscovered(outcome)

                is CalDavDiscovery.Outcome.NeedsAuthentication -> {
                    hostsNeedingAuth = outcome.hosts
                    offerSignIn()
                }

                // No credentials were sent, so this is not a rejection — it is a
                // server that answers RFC 5397 <D:unauthenticated/> on a 200
                // instead of a 401. Sending the user back to the address field
                // would make it permanently unreachable.
                CalDavDiscovery.Outcome.Unauthenticated -> offerSignIn()

                is CalDavDiscovery.Outcome.NotCalDav -> backToAddressOrServer(outcome.cause)

                is CalDavDiscovery.Outcome.Failed -> backToAddressOrServer(outcome.cause)
            }
        }
    }

    /**
     * An email address whose domain has no CalDAV server goes straight to the
     * server field rather than an error with nowhere to go; the email stays as
     * the username. A domain nothing answered for may also be a typo or no
     * network, so that message stays visible on the server field. Any other
     * failure keeps its own message on the email field.
     */
    private fun backToAddressOrServer(cause: CalDavDiscovery.Outcome.Cause) {
        val email = ServiceDiscovery.emailOf(typedInput)
        if (fallbackEmail != null || email == null || cause !in NO_SERVER_FOUND) {
            return backToAddress(cause)
        }
        fallbackEmail = email
        addressInput = ""
        updateState {
            it.copy(
                step = addressStep("", cause.takeIf { it == CalDavDiscovery.Outcome.Cause.UNREACHABLE }),
                quirk = quirkFor(""),
            )
        }
    }

    private fun clearFallback() {
        fallbackEmail = null
    }

    /**
     * The username the credentials step starts with: a re-authentication's, else
     * the email that fell back to the server field — or one typed into it.
     */
    private fun prefilledUsername(): String = suggestedUsername.ifBlank {
        fallbackEmail?.let { ServiceDiscovery.emailOf(typedInput) ?: it }.orEmpty()
    }

    /** Step 3a → re-run discovery with the password the user typed. */
    fun onCredentialsSubmitted() {
        val step = current.step as? AddAccountStep.EnterCredentials ?: return
        if (step.username.isBlank() || step.password.isEmpty()) return
        username = step.username.trim()
        appPassword = step.password

        working(AddAccountMessage.Progress.SigningIn)
        viewModelScope.launch {
            // ⚠️ An error, not a silent unauthenticated retry. serverRootFor
            // cannot parse a host-with-path like cloud.example.com/nextcloud, and
            // passing null here sent no credentials at all — then reported the
            // resulting 401 as "credentials rejected" about a password nothing
            // had tried.
            val root = serverRoot
                ?: return@launch backToAddress(CalDavDiscovery.Outcome.Cause.NOT_AN_ADDRESS)
            val credentials = CalDavGateway.Credentials(username, appPassword, root)
            when (val outcome = gateway.discover(typedInput, credentials)) {
                is CalDavDiscovery.Outcome.Found -> onDiscovered(outcome)

                // A second rejection is the point at which naming the provider's
                // own rule is worth more than repeating "wrong password".
                //
                // ⚠️ Refreshed here too, not only on the browser path. An
                // authenticated PROPFIND reaches further than the anonymous
                // probe — principal, then home sets — so the cross-domain home
                // set that actually caused this 401 is the one most likely to be
                // missing from a list collected before the password was sent,
                // and the user gets "credentials rejected" instead of the
                // diagnostic that names it.
                is CalDavDiscovery.Outcome.NeedsAuthentication -> {
                    hostsNeedingAuth = outcome.hosts
                    credentialsRejected()
                }

                CalDavDiscovery.Outcome.Unauthenticated -> credentialsRejected()

                is CalDavDiscovery.Outcome.NotCalDav -> backToAddressOrServer(outcome.cause)
                is CalDavDiscovery.Outcome.Failed -> backToAddressOrServer(outcome.cause)
            }
        }
    }

    private fun credentialsRejected() = updateState {
        it.copy(
            step = AddAccountStep.EnterCredentials(
                username = username,
                password = "",
                error = quirkHint() ?: crossDomainHint()
                    ?: AddAccountMessage.CredentialsRejected,
            ),
        )
    }

    /** Step 3b → the browser flow, if this looks like a Nextcloud. */
    private suspend fun offerSignIn() {
        val root = serverRoot ?: return backToAddress(CalDavDiscovery.Outcome.Cause.NOT_AN_ADDRESS)
        val flow = gateway.startLoginFlow(root)

        if (flow == null) {
            // Not a Nextcloud, or its login flow is unavailable. Ask for a
            // username and password instead — that is the generic CalDAV path.
            updateState {
                it.copy(
                    step = AddAccountStep.EnterCredentials(
                        username = prefilledUsername(),
                        error = quirkHint(),
                    ),
                )
            }
            return
        }

        // ⚠️ Confirmed before the browser, never alongside it. Everything below
        // — the record, the URL, the poll — assumes the user has agreed to open
        // this page, and for the two cases here they have not been asked yet.
        // Nothing has been minted at this point: the flow is started but no
        // approval can happen until somebody visits the login URL, so declining
        // costs a request and nothing else.
        val insecure = !flow.loginUrl.isHttps
        if (flow.hostMismatch != null || insecure) {
            unconfirmedFlow = flow
            updateState {
                it.copy(
                    step = AddAccountStep.ConfirmBrowser(
                        hostMismatch = flow.hostMismatch,
                        insecure = insecure,
                    ),
                )
            }
            return
        }
        launchBrowser(flow)
    }

    /** The warning is read and the page is wanted anyway. */
    fun onBrowserConfirmed() {
        val flow = unconfirmedFlow ?: return
        unconfirmedFlow = null
        viewModelScope.launch { launchBrowser(flow) }
    }

    /**
     * Hands [flow] to the browser and starts waiting for it.
     *
     * ⚠️ The record is written **here**, not where the flow was started, which
     * is what [NextcloudLoginFlow.Flow]'s doc asks for: persist before launching
     * the browser. Writing it at the confirmation step instead would leave a
     * token behind for a page the user declined to open — a reclaim that can
     * only ever poll a flow nobody will approve.
     *
     * ⚠️ Guarded: a DataStore write can throw, and this runs in viewModelScope,
     * where an escaping exception takes the app down. A flow we failed to write
     * down is a reclaim we will not get, not a reason to lose the sign-in in
     * front of the user.
     */
    private suspend fun launchBrowser(flow: NextcloudLoginFlow.Flow) {
        browserFlow = flow
        runCatching { pendingFlow.remember(flow) }
        updateState {
            it.copy(
                step = AddAccountStep.WaitingForBrowser(hostMismatch = flow.hostMismatch),
                openInBrowser = flow.loginUrl,
            )
        }
        startPolling(flow)
    }

    private fun startPolling(flow: NextcloudLoginFlow.Flow) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // ⚠️ Bounded here, not just by the server's answer. `poll` reports
            // Expired past the flow's deadline, but an unbounded `while (true)`
            // makes this loop's termination somebody else's responsibility — and
            // a gateway that keeps saying "pending" would have it poll forever.
            repeat(MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MILLIS)
                // ⚠️ The *call*, not just what follows it. `pollLoginFlow` is a
                // blocking execute() inside withContext, and withContext throws
                // on return if the job was cancelled meanwhile — so a back
                // gesture landing in that window discarded a 200 the server had
                // already answered, and with it the only copy of a password it
                // had already minted and deleted its flow row for. The persisted
                // flow cannot reclaim that one either: the row is gone, so a
                // later poll can only report expiry. Bounded by the gateway's
                // own per-call budget, and the delay above stays cancellable, so
                // the loop still stops promptly.
                val result = withContext(NonCancellable) { gateway.pollLoginFlow(flow) }
                when (result) {
                    is NextcloudLoginFlow.PollResult.Approved -> {
                        // ⚠️ Uncancellable. The 200 that carried this password is
                        // the only one there will ever be — the server deletes the
                        // flow row before answering — so a cancellation delivered
                        // between that answer and the assignment below spends the
                        // credential with nothing left holding it. Recording it is
                        // what makes handing it back possible.
                        withContext(NonCancellable) {
                            // ⚠️ Forgotten *here*, and nowhere else. This is the
                            // one point where the token stops being worth
                            // anything: the server deleted the flow row before
                            // answering, so there is nothing left to collect.
                            // Every other way out of this step — start over, the
                            // back arrow, a failure, leaving the screen —
                            // abandons a flow the user may still go on to
                            // approve in the browser that is still open, and the
                            // record is what lets the next launch collect that
                            // password and hand it back. A poll past the
                            // deadline reports expiry and clears it.
                            runCatching { pendingFlow.forget() }
                            browserFlow = null
                            username = result.credentials.loginName
                            appPassword = result.credentials.appPassword
                            serverRoot = result.credentials.server
                            // Ours to hand back until an account takes it on.
                            minted = CalDavGateway.Credentials(
                                username,
                                appPassword,
                                result.credentials.server,
                            )
                        }
                        // The server contradicted itself about where it lives.
                        // We recovered, but the setting behind it is the user's
                        // to fix, so say so rather than silently coercing.
                        result.hostMismatch?.let { mismatch ->
                            updateState { it.copy(originMismatch = mismatch) }
                        }
                        working(AddAccountMessage.Progress.ReadingLists)
                        val outcome = gateway.discover(
                            // The server the credentials were *issued by*, not the
                            // host the user typed — the two differ in exactly the
                            // host-mismatch case this flow already models, and
                            // getting it wrong burns the one-shot app password.
                            target = result.credentials.server.toString(),
                            credentials = CalDavGateway.Credentials(
                                username,
                                appPassword,
                                result.credentials.server,
                            ),
                        )
                        when (outcome) {
                            is CalDavDiscovery.Outcome.Found -> onDiscovered(outcome)
                            // ⚠️ Forward what actually happened. Reporting every
                            // outcome as NO_CALENDARS tells someone whose server
                            // named an unresolvable host that their account holds
                            // no task lists, which is both wrong and unactionable.
                            is CalDavDiscovery.Outcome.Failed -> backToAddress(outcome.cause)
                            is CalDavDiscovery.Outcome.NotCalDav -> backToAddress(outcome.cause)
                            // ⚠️ These two carry no Cause, so they used to land
                            // on "signed in, but no task lists" — said of a
                            // credential the server had just rejected. A 401 here
                            // is either a home set outside the domain the
                            // credential is scoped to, which retrying cannot fix
                            // and which only mints a second password, or the
                            // server having a moment.
                            is CalDavDiscovery.Outcome.NeedsAuthentication -> {
                                hostsNeedingAuth = outcome.hosts
                                val stranger = outsideCredentialScope(outcome.hosts)
                                if (stranger != null) {
                                    fatal(AddAccountMessage.OutsideCredentialScope(stranger))
                                } else {
                                    backToAddress(CalDavDiscovery.Outcome.Cause.SERVER_ERROR)
                                }
                            }

                            CalDavDiscovery.Outcome.Unauthenticated ->
                                backToAddress(CalDavDiscovery.Outcome.Cause.SERVER_ERROR)
                        }
                        return@launch
                    }

                    NextcloudLoginFlow.PollResult.Pending -> Unit

                    is NextcloudLoginFlow.PollResult.Expired -> {
                        browserFailed(AddAccountMessage.BrowserApprovalExpired)
                        return@launch
                    }

                    is NextcloudLoginFlow.PollResult.Failed -> {
                        // ⚠️ The cause, never result.reason — that is a bare
                        // status line or a parser's complaint, in whatever
                        // language the server chose. It is for logs.
                        browserFailed(
                            when (result.cause) {
                                NextcloudLoginFlow.PollResult.Cause.RATE_LIMITED ->
                                    AddAccountMessage.BrowserRateLimited
                                NextcloudLoginFlow.PollResult.Cause.MAINTENANCE ->
                                    AddAccountMessage.BrowserMaintenance
                                NextcloudLoginFlow.PollResult.Cause.SERVER_ERROR ->
                                    AddAccountMessage.BrowserFailed
                            },
                        )
                        return@launch
                    }
                }
            }
            browserFailed(AddAccountMessage.BrowserApprovalExpired)
        }
    }

    /**
     * Another go after the browser sign-in failed.
     *
     * An expired flow is gone on the server, so a new one is started. Any other
     * failure leaves the flow approvable: the same page is reopened and polled,
     * because starting a second one would replace the record that lets an
     * approval in the first tab be collected and handed back.
     */
    fun onBrowserRetry() {
        val error = (current.step as? AddAccountStep.WaitingForBrowser)?.error ?: return
        pollJob?.cancel()
        val flow = browserFlow
        if (flow != null && error != AddAccountMessage.BrowserApprovalExpired) {
            viewModelScope.launch { launchBrowser(flow) }
        } else {
            working(AddAccountMessage.Progress.SigningIn)
            viewModelScope.launch { offerSignIn() }
        }
    }

    /** The user says they finished in the browser but nothing arrived. */
    fun onBrowserCancelled() {
        // The one exit that never reaches onStartOver: if approval already
        // landed, that password is about to be replaced by a typed one.
        discardMintedPassword()
        // Also the way out of the confirmation, where the answer to "open this
        // page?" is "no, I'll type a password instead".
        unconfirmedFlow = null
        browserFlow = null
        pollJob?.cancel()
        updateState {
            it.copy(
                step = AddAccountStep.EnterCredentials(username = prefilledUsername(), error = null),
                openInBrowser = null,
            )
        }
    }

    fun onBrowserLaunched() = updateState { it.copy(openInBrowser = null) }

    /**
     * Nothing could open the URL, so there is no approval coming.
     *
     * ⚠️ Without this, a failed launch still cleared `openInBrowser` and left the
     * user watching "waiting for your browser" for the rest of a twenty-minute
     * window, on exactly the browserless devices the Custom Tabs fallback exists
     * for. Nothing was minted — the flow was never opened — so there is nothing
     * to hand back; the poll is stopped because it is polling for an approval
     * that cannot happen.
     */
    fun onBrowserUnavailable() {
        pollJob?.cancel()
        browserFailed(AddAccountMessage.BrowserUnavailable)
    }

    fun onListToggled(url: HttpUrl) {
        val step = current.step as? AddAccountStep.ChooseLists ?: return
        val selected = if (url in step.selected) step.selected - url else step.selected + url
        updateState { it.copy(step = step.copy(selected = selected)) }
    }

    fun onSave() {
        val step = current.step as? AddAccountStep.ChooseLists ?: return
        val discovered = found ?: return
        val chosen = step.collections.filter { it.url in step.selected }.toSet()

        if (username.isBlank() || appPassword.isEmpty()) {
            // A server that needed no credentials at all would otherwise be saved
            // with an empty username and password, and fail every later sync.
            updateState { it.copy(step = AddAccountStep.EnterCredentials()) }
            return
        }

        working(AddAccountMessage.Progress.Saving)
        viewModelScope.launch {
            val outcome = runCatching {
                repository.create(
                    displayName = accountName(),
                    username = username,
                    appPassword = appPassword,
                    found = discovered,
                    selected = chosen,
                    reauthenticating = reauthAccountId,
                )
            }.getOrElse {
                // A constraint violation or an IO failure in DataStore must not
                // take the app down and leave the spinner up forever.
                AccountRepository.Outcome.CredentialFailed(
                    AccountRepository.Outcome.Cause.NOT_SAVED,
                    it.message.orEmpty(),
                )
            }
            when (outcome) {
                is AccountRepository.Outcome.Created -> {
                    // Ownership passes to the account, which revokes on removal —
                    // but only if this is the password it was saved with. Every
                    // route from a minted password to a typed one already
                    // discards on the way, so the else branch should be dead;
                    // it is here because the failure it guards is silent and
                    // delayed. The account syncs once and then 401s for ever,
                    // long after anyone would connect it to adding it.
                    //
                    // Cleared before the state update: an exception there must not
                    // leave a saved account's own credential queued for revoking.
                    if (minted?.password == appPassword) minted = null else discardMintedPassword()
                    updateState { it.copy(step = summaryOf(discovered, chosen)) }
                }

                AccountRepository.Outcome.AlreadyExists ->
                    fatal(AddAccountMessage.AlreadyExists)

                AccountRepository.Outcome.ExternalStorage ->
                    fatal(AddAccountMessage.ExternalStorage)

                is AccountRepository.Outcome.CredentialFailed -> fatal(
                    when (outcome.cause) {
                        AccountRepository.Outcome.Cause.KEYSTORE_REFUSED ->
                            AddAccountMessage.KeystoreRefused
                        AccountRepository.Outcome.Cause.NOT_SAVED -> AddAccountMessage.NotSaved
                    },
                )
            }
        }
    }

    /** The receipt is read; hand back to whoever hosts the flow. */
    fun onSummaryDone() = updateState { it.copy(step = AddAccountStep.Done) }

    /**
     * Step back inside the flow, or report that there is nowhere left to go.
     *
     * Only the two steps that have written nothing can be stepped back from.
     * From the browser step onwards, "back" means abandoning a flow the server
     * is still holding open, and from the list picker it would mean re-running
     * discovery — both are the host's business, which is what `false` asks for.
     *
     * ⚠️ The credentials go with it. `EnterCredentials` is reachable *from* an
     * approved browser flow, so stepping back to the address without this leaves
     * a minted password behind and a typed address that could carry it to a
     * different server — the same leak `onAddressSubmitted` guards.
     */
    fun onBackWithin(): Boolean = when (current.step) {
        // Back into the errand when there was one, so the steps can be re-read
        // without starting the flow again — that is the screen people return to.
        is AddAccountStep.EnterAddress -> {
            val email = fallbackEmail
            if (email != null) {
                clearFallback()
                addressInput = email
                typedInput = email
                updateState {
                    it.copy(step = addressStep(email), fatal = null, quirk = quirkFor(email))
                }
            } else {
                updateState { it.copy(step = providerOrSetupStep(), fatal = null) }
            }
            true
        }

        is AddAccountStep.PrepareAccess -> {
            updateState {
                it.copy(step = AddAccountStep.ChooseProvider(choice), fatal = null)
            }
            true
        }

        is AddAccountStep.EnterCredentials -> {
            discardMintedPassword()
            username = ""
            appPassword = ""
            found = null
            hostsNeedingAuth = emptyList()
            updateState {
                it.copy(step = addressStep(typedInput), fatal = null, originMismatch = null)
            }
            true
        }

        // ⚠️ Steps back, unlike the browser step it precedes. The note it
        // carries blames an address, and "that address is wrong" is the most
        // likely reading of it — so the way out has to reach the field that
        // holds it. Nothing has been approved, minted or written down yet, so
        // this costs the flow the server is holding and nothing else.
        is AddAccountStep.ConfirmBrowser -> {
            unconfirmedFlow = null
            updateState { it.copy(step = addressStep(typedInput), fatal = null) }
            true
        }

        else -> false
    }

    /** Whatever precedes the address: the errand if the service sets one, else the picker. */
    private fun providerOrSetupStep(): AddAccountStep {
        val picked = choice ?: return AddAccountStep.ChooseProvider(null)
        val quirk = picked.provider?.let(ServerQuirk::forProvider)
        return if (quirk?.hasSetupSteps == true) {
            AddAccountStep.PrepareAccess(picked, quirk)
        } else {
            AddAccountStep.ChooseProvider(picked)
        }
    }

    /**
     * Full reset, including the credentials.
     *
     * Called on "start over", on leaving the screen, and after a successful add.
     * The ViewModel is scoped to the Settings back-stack entry and the flow is an
     * `AnimatedVisibility` section inside it, so it outlives the composable:
     * without this, re-opening "Add account" would find `step = Done` and bounce
     * straight back out, and a second account would be created with the *first*
     * account's username and app password.
     */
    fun onStartOver() {
        discardMintedPassword()
        unconfirmedFlow = null
        browserFlow = null
        choice = null
        pollJob?.cancel()
        pollJob = null
        found = null
        addressInput = ""
        typedInput = ""
        serverRoot = null
        username = ""
        appPassword = ""
        hostsNeedingAuth = emptyList()
        reauthAccountId = null
        suggestedUsername = ""
        fallbackEmail = null
        current = AddAccountUiState()
    }

    override fun onCleared() {
        abandonMintedPassword()
        pollJob?.cancel()
    }

    /** [onCleared] is protected, and the abandonment is worth testing. */
    internal fun abandonMintedPassword() = discardMintedPassword()

    /**
     * Hand back a password nothing is going to use.
     *
     * Fire and forget: the user is already leaving, the call is best-effort by
     * the module's own contract, and it carries its own 5s budget. A failure is
     * not reported — telling someone we could not clean up a credential they
     * never knew existed, while they are escaping a failure, is worse than the
     * row it leaves behind.
     */
    private fun discardMintedPassword() {
        val credentials = minted ?: return
        minted = null
        // The scope has no exception handler, so anything escaping here takes the
        // app down — for a courtesy call whose failures are deliberately silent.
        appScope.launch { runCatching { gateway.revokeIssuedAppPassword(credentials) } }
    }

    // -------------------------------------------------------- saved state

    /**
     * Writes down where the flow is, on every state change.
     *
     * A collector rather than a line in each of the fifteen places that update
     * the state: one of those would eventually be added without it, and the
     * failure is invisible until a process dies at exactly that step.
     */
    private fun record(current: AddAccountUiState) {
        handle[KEY_HAS_SETUP] = current.hasSetupStep
        handle[KEY_STEP] = when (current.step) {
            is AddAccountStep.ChooseProvider -> STEP_PROVIDER
            is AddAccountStep.PrepareAccess -> STEP_SETUP
            is AddAccountStep.EnterAddress -> STEP_ADDRESS
            is AddAccountStep.EnterCredentials -> STEP_CREDENTIALS
            // ⚠️ Recorded as the address, which is where a restore puts them.
            // Neither can be resumed into: the browser is holding a flow this
            // process no longer owns, and `PendingLoginFlowStore.reclaim` is
            // what deals with that on the next open. Recording them as
            // themselves would only mean deciding the same thing twice.
            is AddAccountStep.ConfirmBrowser, is AddAccountStep.WaitingForBrowser -> STEP_ADDRESS
            is AddAccountStep.Working -> STEP_WORKING
            is AddAccountStep.ChooseLists -> STEP_LISTS
            is AddAccountStep.Summary -> STEP_SUMMARY
            AddAccountStep.Done -> STEP_DONE
        }
        // ⚠️ The username as it is being *typed*, not only as submitted. The
        // field is the same thing [username] holds — `onCredentialsSubmitted`
        // merely copies it across — and without this a restore from a
        // half-filled sign-in came back with an empty name beside a password
        // field, which reads as having lost both.
        (current.step as? AddAccountStep.EnterCredentials)?.let { handle[KEY_USERNAME] = it.username }
        // Null clears the key, so a step's payload never outlives the step.
        handle[KEY_SELECTED] = (current.step as? AddAccountStep.ChooseLists)
            ?.selected
            ?.mapTo(ArrayList()) { it.toString() }
        (current.step as? AddAccountStep.Summary).let { summary ->
            handle[KEY_SUMMARY_PROVIDER] = summary?.provider?.name
            handle[KEY_SUMMARY_TITLE] = summary?.title
            handle[KEY_SUMMARY_SECONDARY] = summary?.secondary
            handle[KEY_SUMMARY_LISTS] = summary?.lists?.let(::ArrayList)
        }
    }

    /**
     * Picks the flow up where a killed process left it, or does nothing.
     *
     * Its own writes go back through [current] and so re-record what they just
     * read, which is a no-op by construction — the step it restores to is the
     * step it read, and a browser step deliberately records as the address it
     * restores to.
     */
    private fun restore() {
        val step = handle.get<String>(KEY_STEP) ?: return
        val hasSetup: Boolean = handle[KEY_HAS_SETUP] ?: false
        // A password the server minted before the process died is the account's
        // password now; everything downstream reads it from here.
        minted?.let {
            username = it.username
            appPassword = it.password
            serverRoot = it.origin
        }
        when (step) {
            STEP_SETUP -> {
                val picked = choice
                val quirk = picked?.provider?.let(ServerQuirk::forProvider)
                current = if (picked != null && quirk?.hasSetupSteps == true) {
                    restored(AddAccountStep.PrepareAccess(picked, quirk), hasSetup)
                } else {
                    restored(AddAccountStep.ChooseProvider(picked), hasSetup)
                }
            }

            STEP_ADDRESS -> current = restored(addressStep(addressInput), hasSetup)

            // The username comes back, the password does not — it is the one the
            // user typed, and a restored password field is a surprise rather
            // than a convenience.
            STEP_CREDENTIALS ->
                current = restored(AddAccountStep.EnterCredentials(username), hasSetup)

            // Both need `found`, which is not saved. One re-read of the server
            // rebuilds it, and re-ticks whatever the user had ticked.
            STEP_WORKING, STEP_LISTS -> resumeDiscovery(hasSetup)

            STEP_SUMMARY ->
                current = restored(restoredSummary() ?: AddAccountStep.Done, hasSetup)

            STEP_DONE -> current = restored(AddAccountStep.Done, hasSetup)

            else -> current = restored(AddAccountStep.ChooseProvider(choice), hasSetup)
        }
    }

    private fun restored(step: AddAccountStep, hasSetup: Boolean) = AddAccountUiState(
        step = step,
        hasSetupStep = hasSetup,
        reauthenticating = reauthAccountId != null,
        // Derived rather than saved: it is a pure function of the choice and the
        // address, both of which are.
        quirk = quirkFor(addressInput),
    )

    /**
     * Re-reads the collections for a flow that died holding a credential.
     *
     * ⚠️ The credential is the whole test. Without one there is nothing to ask
     * the server with, so the flow goes back to the address — which is the step
     * that can get one — rather than to a spinner that will never resolve.
     */
    private fun resumeDiscovery(hasSetup: Boolean) {
        val root = serverRoot
        val target = typedInput.ifBlank { root?.toString().orEmpty() }
        if (root == null || appPassword.isEmpty() || target.isBlank()) {
            current = restored(addressStep(addressInput), hasSetup)
            return
        }
        val selection = handle.get<ArrayList<String>>(KEY_SELECTED)
            ?.mapNotNull { it.toHttpUrlOrNull() }
            ?.toSet()
        current = restored(
            AddAccountStep.Working(AddAccountMessage.Progress.ReadingLists),
            hasSetup,
        )
        viewModelScope.launch {
            val credentials = CalDavGateway.Credentials(username, appPassword, root)
            when (val outcome = gateway.discover(target, credentials)) {
                is CalDavDiscovery.Outcome.Found -> onDiscovered(outcome, selection)
                is CalDavDiscovery.Outcome.NotCalDav -> backToAddressOrServer(outcome.cause)
                is CalDavDiscovery.Outcome.Failed -> backToAddressOrServer(outcome.cause)
                // The credential we were holding is no longer accepted, which on
                // this path is indistinguishable from never having had one.
                is CalDavDiscovery.Outcome.NeedsAuthentication -> {
                    hostsNeedingAuth = outcome.hosts
                    credentialsRejected()
                }
                CalDavDiscovery.Outcome.Unauthenticated -> credentialsRejected()
            }
        }
    }

    private fun restoredSummary(): AddAccountStep.Summary? {
        val title = handle.get<String>(KEY_SUMMARY_TITLE) ?: return null
        return AddAccountStep.Summary(
            provider = handle.get<String>(KEY_SUMMARY_PROVIDER)
                ?.let { name -> CalDavProvider.entries.firstOrNull { it.name == name } },
            title = title,
            secondary = handle[KEY_SUMMARY_SECONDARY],
            username = username,
            lists = handle.get<ArrayList<String>>(KEY_SUMMARY_LISTS).orEmpty(),
        )
    }

    private fun choiceOf(token: String): ProviderChoice? = if (token == OTHER_SERVER_TOKEN) {
        ProviderChoice.OtherServer
    } else {
        CalDavProvider.entries.firstOrNull { it.name == token }?.let(ProviderChoice::Service)
    }

    // ------------------------------------------------------------- internals

    /**
     * @param preselected what the user had ticked before a restore, or null on
     *   an ordinary run. Intersected with what the server still offers, so a
     *   collection that has since been unshared cannot come back ticked.
     */
    private fun onDiscovered(
        outcome: CalDavDiscovery.Outcome.Found,
        preselected: Set<HttpUrl>? = null,
    ) {
        found = outcome
        if (outcome.collections.isEmpty()) {
            fatal(AddAccountMessage.NoUsableLists)
            return
        }
        val offered = outcome.collections.map { it.url }.toSet()
        updateState {
            it.copy(
                step = AddAccountStep.ChooseLists(
                    collections = outcome.collections,
                    // Everything writable, pre-ticked: the common case is "all of
                    // them", and a read-only share is more often noise than not.
                    selected = preselected?.intersect(offered)
                        ?: outcome.collections.filterNot { c -> c.readOnly }
                            .map { c -> c.url }
                            .toSet(),
                ),
                openInBrowser = null,
            )
        }
    }

    private fun working(message: AddAccountMessage.Progress) =
        updateState { it.copy(step = AddAccountStep.Working(message), fatal = null) }

    /**
     * A dead end. The step goes back to the address underneath, so the spinner is
     * actually gone rather than merely hidden behind the message — the screen
     * happens to render `fatal` first, and relying on that leaves the state
     * lying about what it is doing.
     */
    private fun fatal(reason: AddAccountMessage) = updateState {
        it.copy(step = addressStep(typedInput), fatal = reason)
    }

    /**
     * ⚠️ Carries the [CalDavDiscovery.Outcome.Cause], never the detail string.
     *
     * The detail is the server's own words — routinely a bare status line, often
     * in a language the user does not read, and always outside `strings.xml`.
     * The screen turns the cause into a translated sentence; nothing renders the
     * detail.
     */
    private fun backToAddress(cause: CalDavDiscovery.Outcome.Cause) = updateState {
        it.copy(step = addressStep(typedInput, cause))
    }

    /**
     * The address step, carrying the choice that framed the question.
     *
     * The choice can only be null for a flow that never had a step 1 — nothing
     * reaches this without one — and an unnamed server is the honest fallback.
     */
    private fun addressStep(
        input: String,
        error: CalDavDiscovery.Outcome.Cause? = null,
    ) = AddAccountStep.EnterAddress(
        choice = choice ?: ProviderChoice.OtherServer,
        input = input,
        error = error,
        manualServer = fallbackEmail != null,
    )

    /** What was actually set up, named the way the accounts list will name it. */
    private fun summaryOf(
        discovered: CalDavDiscovery.Outcome.Found,
        chosen: Set<TaskCollection>,
    ): AddAccountStep.Summary {
        // The principal, not the typed address: it is what the account stores and
        // what identifies self-hosted software, so the receipt and the accounts
        // list cannot disagree about what this account is.
        val provider = CalDavProvider.forPrincipal(discovered.principal)
        val host = discovered.principal.host.removePrefix("www.")
        val hosted = provider?.hosted == true
        return AddAccountStep.Summary(
            provider = provider,
            title = if (hosted) provider.label else host,
            secondary = if (hosted) host else provider?.label,
            username = username,
            lists = chosen.map { it.displayName ?: it.url.encodedPath },
        )
    }

    private fun browserFailed(reason: AddAccountMessage) = updateState {
        // Keeps whatever the step was carrying. The host-mismatch note is often
        // the *explanation* for the failure, so dropping it removes the warning
        // exactly when it becomes worth reading.
        val step = it.step as? AddAccountStep.WaitingForBrowser
            ?: AddAccountStep.WaitingForBrowser()
        it.copy(step = step.copy(error = reason), openInBrowser = null)
    }

    private fun updateCredentials(transform: (AddAccountStep.EnterCredentials) -> AddAccountStep.EnterCredentials) {
        val step = current.step as? AddAccountStep.EnterCredentials ?: return
        updateState { it.copy(step = transform(step)) }
    }

    /** The chosen service's quirk, else the one the typed host implies. */
    private fun quirkFor(input: String): ServerQuirk? =
        choice?.provider?.let(ServerQuirk::forProvider) ?: ServerQuirk.forInput(input)

    /** The provider-specific reason a correct-looking password gets rejected. */
    private fun quirkHint(): AddAccountMessage? =
        when (val quirk = quirkFor(typedInput)) {
            ServerQuirk.FASTMAIL_APP_PASSWORD,
            ServerQuirk.ICLOUD_APP_SPECIFIC_PASSWORD,
            -> AddAccountMessage.Quirk(quirk)
            else -> null
        }

    /**
     * A home set on a different registrable domain than the account's own is
     * legal (RFC 4791 §6.2.1) but unreachable for us: the credential is scoped to
     * one domain. Better to name it than to leave "wrong password" standing.
     */
    private fun crossDomainHint(): AddAccountMessage? =
        outsideCredentialScope(hostsNeedingAuth)?.let {
            AddAccountMessage.OutsideCredentialScope(it)
        }

    /**
     * The first host the credential will never be offered to, if any.
     *
     * ⚠️ The same boundary `CalDavHttp` scopes the credential by, derived the
     * same way. A `substringAfter('.')` split computes "com" for
     * `example.com` — so every host ending in `com` reads as in-domain, the
     * diagnostic never fires, and `notexample.com` reads as in-domain too.
     */
    private fun outsideCredentialScope(hosts: List<String>): String? {
        val root = serverRoot ?: return null
        val scope = root.topPrivateDomain() ?: root.host
        return hosts.firstOrNull { host ->
            // OkHttp hands back IPv6 literals unbracketed, which will not parse
            // again. Unparseable reads as in-scope: the cost of a diagnostic that
            // stays quiet is nothing, and the caller now treats out-of-scope as
            // fatal — so failing the other way strands a homelab on [::1].
            val literal = if (':' in host) "[$host]" else host
            val candidate = "https://$literal".toHttpUrlOrNull() ?: return@firstOrNull false
            !(candidate.topPrivateDomain() ?: candidate.host).equals(scope, ignoreCase = true)
        }
    }

    private fun accountName(): String =
        username.takeIf { it.isNotBlank() }?.let { "$it@${serverRoot?.host.orEmpty()}" }
            ?: typedInput

    internal companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** The server-side lifetime is 1200s; this covers it and then stops. */
        const val MAX_POLL_ATTEMPTS = 600

        /**
         * ⚠️ Tokens, not `Enum.name` or an ordinal. These strings are written
         * into a bundle by one build and read back by whichever build the system
         * hands the state to after an update, so they have to be stable against
         * a step being renamed, added or reordered — and an unknown one has to
         * be survivable, which is what `restore`'s `else` is for.
         */
        const val STEP_PROVIDER = "provider"
        const val STEP_SETUP = "setup"
        const val STEP_ADDRESS = "address"
        const val STEP_CREDENTIALS = "credentials"
        const val STEP_WORKING = "working"
        const val STEP_LISTS = "lists"
        const val STEP_SUMMARY = "summary"
        const val STEP_DONE = "done"

        /** Cannot collide with a [CalDavProvider] name, which is what it stands beside. */
        const val OTHER_SERVER_TOKEN = "other-server"

        const val KEY_STEP = "add_account.step"
        const val KEY_CHOICE = "add_account.choice"
        const val KEY_HAS_SETUP = "add_account.has_setup"
        const val KEY_ADDRESS_INPUT = "add_account.address_input"
        const val KEY_TYPED_INPUT = "add_account.typed_input"
        const val KEY_SERVER_ROOT = "add_account.server_root"
        const val KEY_USERNAME = "add_account.username"
        const val KEY_HOSTS_NEEDING_AUTH = "add_account.hosts_needing_auth"
        const val KEY_SELECTED = "add_account.selected"
        const val KEY_MINTED_USERNAME = "add_account.minted_username"
        const val KEY_MINTED_PASSWORD = "add_account.minted_password"
        const val KEY_MINTED_ORIGIN = "add_account.minted_origin"
        const val KEY_SUMMARY_PROVIDER = "add_account.summary_provider"
        const val KEY_SUMMARY_TITLE = "add_account.summary_title"
        const val KEY_SUMMARY_SECONDARY = "add_account.summary_secondary"
        const val KEY_SUMMARY_LISTS = "add_account.summary_lists"
        const val KEY_REAUTH_ACCOUNT = "add_account.reauth_account"
        const val KEY_SUGGESTED_USERNAME = "add_account.suggested_username"
        const val KEY_FALLBACK_EMAIL = "add_account.fallback_email"

        /** What sends an email to the server field: no CalDAV on its domain, or nothing answering for it. */
        val NO_SERVER_FOUND = setOf(
            CalDavDiscovery.Outcome.Cause.NOT_A_DAV_SERVER,
            CalDavDiscovery.Outcome.Cause.NO_CALENDAR_SUPPORT,
            CalDavDiscovery.Outcome.Cause.UNREACHABLE,
        )

        /**
         * What to type for a server, from the principal an account stored.
         *
         * The root, not the principal: Nextcloud's login flow hangs off the root,
         * and on a subpath install that is everything before `/remote.php/`.
         */
        internal fun serverAddressOf(principal: HttpUrl): String {
            val url = principal.toString()
            val dav = url.indexOf("/remote.php/")
            return if (dav >= 0) url.substring(0, dav) else principal.resolve("/").toString().trimEnd('/')
        }
    }
}
