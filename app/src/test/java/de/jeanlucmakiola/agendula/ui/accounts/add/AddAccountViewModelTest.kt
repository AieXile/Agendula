package de.jeanlucmakiola.agendula.ui.accounts.add

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.sync.AccountCreator
import de.jeanlucmakiola.agendula.data.sync.AccountRepository
import de.jeanlucmakiola.agendula.data.sync.CalDavGateway
import de.jeanlucmakiola.agendula.data.sync.LoginFlowRecord
import de.jeanlucmakiola.caldav.CalDavDiscovery
import de.jeanlucmakiola.caldav.CalDavProvider
import de.jeanlucmakiola.caldav.NextcloudLoginFlow
import de.jeanlucmakiola.caldav.ServerQuirk
import de.jeanlucmakiola.caldav.TaskCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The sign-in state machine.
 *
 * Worth testing closely: it decides where five discovery outcomes lead, when a
 * one-shot app password is spent, and which host a credential is scoped to —
 * all of which fail quietly, and none of which needs a server to exercise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val gateway = FakeGateway()
    private val creator = FakeCreator()
    private val record = FakeLoginFlowRecord()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    /**
     * ⚠️ Not `backgroundScope`: `advanceUntilIdle` does not run its work, so a
     * revocation launched there would silently never happen and every assertion
     * about it would pass for the wrong reason. This scope shares the scheduler
     * as ordinary work.
     */
    private val appScope = CoroutineScope(dispatcher)

    /**
     * A fresh wizard. [handle] is a seam for the restore tests: handing the same
     * one to a second view model is exactly what process death does.
     */
    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        AddAccountViewModel(creator, gateway, record, handle, appScope)

    @Nested
    inner class TheProviderStep {

        @Test
        fun `a service with an errand puts it on its own screen first`() =
            runTest(dispatcher) {
                val vm = viewModel()
                vm.onProviderChosen(ProviderChoice.Service(CalDavProvider.FASTMAIL))

                // The whole reason the picker earns a step: Fastmail rejects the
                // account password with a plain 401, and saying so afterwards is
                // saying it too late. It is a screen rather than a note because
                // it is four actions in someone else's web app.
                val step = vm.state.value.step as AddAccountStep.PrepareAccess
                assertThat(step.quirk).isEqualTo(ServerQuirk.FASTMAIL_APP_PASSWORD)

                vm.onSetupAcknowledged()
                assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.EnterAddress::class.java)
            }

        @Test
        fun `a service we cannot sync with does not advance`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.onProviderChosen(ProviderChoice.Service(CalDavProvider.GOOGLE))

            // Picking the wrong service is a tap to undo, so it stays on the
            // picker rather than ending the flow the way a typed address does.
            assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.ChooseProvider::class.java)
            assertThat(vm.state.value.quirk).isEqualTo(ServerQuirk.GOOGLE_UNSUPPORTED)
        }

        @Test
        fun `a service with nothing to prepare goes straight to the address`() =
            runTest(dispatcher) {
                val vm = viewModel()
                val choice = ProviderChoice.Service(CalDavProvider.POSTEO)
                // One tap, no Continue: the row is the decision.
                vm.onProviderChosen(choice)

                // The step carries the choice because the question it asks
                // depends on it: a hosted service is reached by an email address,
                // a server by a URL.
                assertThat((vm.state.value.step as AddAccountStep.EnterAddress).choice)
                    .isEqualTo(choice)
            }

        @Test
        fun `the address steps back to the picker`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")

            assertThat(vm.onBackWithin()).isTrue()
            assertThat(vm.state.value.step)
                .isEqualTo(AddAccountStep.ChooseProvider(ProviderChoice.OtherServer))
        }

        @Test
        fun `the address steps back into the errand when there was one`() =
            runTest(dispatcher) {
                val vm = viewModel()
                vm.onProviderChosen(ProviderChoice.Service(CalDavProvider.ICLOUD))
                vm.onSetupAcknowledged()

                // Back to the instructions, not past them: that is the screen
                // someone returns to when they lose the app password.
                assertThat(vm.onBackWithin()).isTrue()
                assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.PrepareAccess::class.java)
                assertThat(vm.onBackWithin()).isTrue()
                assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.ChooseProvider::class.java)
            }

        @Test
        fun `the picker is the end of the road, so the host takes the back press`() =
            runTest(dispatcher) {
                val vm = viewModel()
                vm.onProviderChosen(ProviderChoice.OtherServer)
                vm.onBackWithin()

                // False is what makes the screen abandon rather than swallow the
                // gesture — and abandoning is what runs onStartOver, which is the
                // only thing that stops a poll and hands back a minted password.
                assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.ChooseProvider::class.java)
                assertThat(vm.onBackWithin()).isFalse()
            }

        @Test
        fun `there is nowhere to step back to from the list picker`() = runTest(dispatcher) {
            val vm = signedIn()

            // Back there would mean re-running discovery, so the host is left to
            // decide what leaving means.
            assertThat(vm.onBackWithin()).isFalse()
        }
    }

    @Nested
    inner class TheAddressStep {

        @Test
        fun `Google is refused before any request is made`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.enterAddress("me@gmail.com")
            vm.onAddressSubmitted()
            advanceUntilIdle()

            // It supports neither VTODO nor MKCALENDAR — its own docs say so — so
            // a 401 the user cannot act on is the wrong answer.
            assertThat(vm.state.value.fatal).isEqualTo(AddAccountMessage.GoogleUnsupported)
            assertThat(gateway.discoveries).isEmpty()
        }

        @Test
        fun `a provider quirk is named while the user is still typing`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.enterAddress("me@fastmail.com")
            assertThat(vm.state.value.quirk).isNotNull()
        }

        @Test
        fun `an unauthenticated 200 offers sign-in rather than bouncing back`() =
            runTest(dispatcher) {
                // No credentials were sent, so RFC 5397's <D:unauthenticated/> is
                // not a rejection. Sending the user back to the address field
                // would make such a server permanently unreachable.
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.Unauthenticated
                gateway.loginFlow = null

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()

                assertThat(vm.state.value.step)
                    .isInstanceOf(AddAccountStep.EnterCredentials::class.java)
            }

        @Test
        fun `a server that is not CalDAV says so on the address step`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NotCalDav(
                CalDavDiscovery.Outcome.Cause.NO_CALENDAR_SUPPORT,
                "no calendar-access",
            )

            val vm = viewModel()
            vm.enterAddress("https://example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()

            val step = vm.state.value.step as AddAccountStep.EnterAddress
            // ⚠️ A cause, never the server's own words — the screen owns the
            // wording, and a raw status line bypasses strings.xml entirely.
            assertThat(step.error).isEqualTo(CalDavDiscovery.Outcome.Cause.NO_CALENDAR_SUPPORT)
            // The input survives, so the user can correct it rather than retype it.
            assertThat(step.input).isEqualTo("https://example.com/")
        }
    }

    @Nested
    inner class TheBrowserStep {

        @Test
        fun `discovery after approval targets the server that issued the credentials`() =
            runTest(dispatcher) {
                // The host-mismatch case: the user typed one host, the server
                // reported another. Probing the typed one with a credential scoped
                // to the reported one 401s and burns the one-shot app password.
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("cloud.example.com"),
                )
                gateway.loginFlow = flow()
                gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                    NextcloudLoginFlow.Credentials(
                        server = "https://dav.example.com/".toHttpUrl(),
                        loginName = "me",
                        appPassword = "app-pw",
                    ),
                )
                gateway.discoveryOutcomes += found(collection("Tasks"))

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()

                assertThat(gateway.discoveries.last().target).isEqualTo("https://dav.example.com/")
                assertThat(gateway.discoveries.last().credentials?.origin.toString())
                    .isEqualTo("https://dav.example.com/")
            }

        @Test
        fun `an origin the server got wrong is surfaced and stays visible`() =
            runTest(dispatcher) {
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("cloud.example.com"),
                )
                gateway.loginFlow = flow()
                gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                    credentials = NextcloudLoginFlow.Credentials(
                        server = "https://cloud.example.com/".toHttpUrl(),
                        loginName = "me",
                        appPassword = "app-pw",
                    ),
                    hostMismatch = NextcloudLoginFlow.HostMismatch(
                        expected = "cloud.example.com",
                        actual = "nextcloud",
                    ),
                )
                gateway.discoveryOutcomes += found(collection("Tasks"))

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()

                // Sticky: it is learned mid-flow, but the setting it blames is
                // what the user has to go and fix afterwards.
                assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.ChooseLists::class.java)
                assertThat(vm.state.value.originMismatch?.actual).isEqualTo("nextcloud")
            }

        @Test
        fun `a discovery failure after approval reports what actually failed`() =
            runTest(dispatcher) {
                // The first outcome is the unauthenticated probe that sends us
                // into the browser flow; the second is the discovery that runs
                // once the credentials come back.
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("cloud.example.com"),
                )
                gateway.loginFlow = flow()
                gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                    NextcloudLoginFlow.Credentials(
                        server = "https://cloud.example.com/".toHttpUrl(),
                        loginName = "me",
                        appPassword = "app-pw",
                    ),
                )
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.Failed(
                    CalDavDiscovery.Outcome.Cause.UNREACHABLE,
                    "nextcloud: nodename nor servname provided",
                )

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()

                // ⚠️ Collapsing this to NO_CALENDARS tells someone with a DNS
                // failure that their account holds no task lists — wrong, and
                // nothing they can act on.
                val step = vm.state.value.step as AddAccountStep.EnterAddress
                assertThat(step.error).isEqualTo(CalDavDiscovery.Outcome.Cause.UNREACHABLE)
            }

        @Test
        fun `the login URL is handed to the browser exactly once`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            // runCurrent, not advanceUntilIdle: the poll loop's first act is a
            // delay, so this settles discovery without consuming the flow.
            runCurrent()

            assertThat(vm.state.value.openInBrowser).isNotNull()
            vm.onBrowserLaunched()
            assertThat(vm.state.value.openInBrowser).isNull()
            vm.onStartOver()
        }

        @Test
        fun `polling stops on its own rather than running forever`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()
            // The fake never approves, so only the loop's own bound ends it.

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()

            val step = vm.state.value.step as AddAccountStep.WaitingForBrowser
            assertThat(step.error).isNotNull()
        }

        @Test
        fun `a failed sign-in is retried on the same flow`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()
            gateway.pollResults += NextcloudLoginFlow.PollResult.Failed(
                NextcloudLoginFlow.PollResult.Cause.SERVER_ERROR,
                "HTTP 500",
            )

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            runCurrent()
            vm.onBrowserLaunched()
            advanceTimeBy(3_000)
            runCurrent()
            assertThat((vm.state.value.step as AddAccountStep.WaitingForBrowser).error)
                .isEqualTo(AddAccountMessage.BrowserFailed)

            vm.onBrowserRetry()
            runCurrent()

            // The old flow is still approvable, so it is reopened rather than
            // replaced: a second flow would overwrite the record that collects
            // an approval made in the first tab.
            val step = vm.state.value.step as AddAccountStep.WaitingForBrowser
            assertThat(step.error).isNull()
            assertThat(vm.state.value.openInBrowser).isNotNull()
            assertThat(gateway.loginFlowsStarted).isEqualTo(1)
            vm.onStartOver()
        }

        @Test
        fun `an expired sign-in is retried with a fresh flow`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()
            gateway.pollResults += NextcloudLoginFlow.PollResult.Expired("window closed")

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            runCurrent()
            vm.onBrowserLaunched()
            advanceTimeBy(3_000)
            runCurrent()

            vm.onBrowserRetry()
            runCurrent()

            assertThat((vm.state.value.step as AddAccountStep.WaitingForBrowser).error).isNull()
            assertThat(gateway.loginFlowsStarted).isEqualTo(2)
            vm.onStartOver()
        }

        @Test
        fun `a server with no login flow falls back to a password`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = null

            val vm = viewModel()
            vm.enterAddress("https://baikal.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()

            assertThat(vm.state.value.step)
                .isInstanceOf(AddAccountStep.EnterCredentials::class.java)
        }
    }

    /**
     * The gate in front of the Custom Tab.
     *
     * The login page is where the **account** password is typed, and the note
     * about it used to arrive in the same state update as the URL — so it drew
     * behind a browser that was already open, which is not a confirmation.
     */
    @Nested
    inner class ConfirmingTheLoginPage {

        @Test
        fun `a host mismatch stops short of opening the browser`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow(
                hostMismatch = NextcloudLoginFlow.HostMismatch(
                    expected = "cloud.example.com",
                    actual = "nextcloud",
                ),
            )

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            runCurrent()

            val step = vm.state.value.step as AddAccountStep.ConfirmBrowser
            assertThat(step.hostMismatch?.actual).isEqualTo("nextcloud")
            // The two halves of the defect: no URL handed out, and nothing
            // written down for a page nobody has agreed to open.
            assertThat(vm.state.value.openInBrowser).isNull()
            assertThat(record.remembered).isNull()
        }

        @Test
        fun `a cleartext login page is confirmed too`() = runTest(dispatcher) {
            // requireSecureOrigin cannot fire here: the base was typed as http,
            // so a cleartext login URL is consistent rather than a downgrade —
            // and it is still the page the account password is typed into.
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow(loginUrl = "http://cloud.example.com/login/flow")

            val vm = viewModel()
            vm.enterAddress("http://cloud.example.com/")
            vm.onAddressSubmitted()
            runCurrent()

            assertThat((vm.state.value.step as AddAccountStep.ConfirmBrowser).insecure).isTrue()
            assertThat(vm.state.value.openInBrowser).isNull()
        }

        @Test
        fun `confirming opens the page and starts waiting for it`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow(
                hostMismatch = NextcloudLoginFlow.HostMismatch("cloud.example.com", "nextcloud"),
            )

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            runCurrent()
            vm.onBrowserConfirmed()
            runCurrent()

            assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.WaitingForBrowser::class.java)
            assertThat(vm.state.value.openInBrowser).isNotNull()
            // Persisted here rather than at the start, which is what the flow's
            // own doc asks for: before the browser, not before the question.
            assertThat(record.remembered).isEqualTo(gateway.loginFlow)
        }

        @Test
        fun `declining goes back to the address that produced the note`() =
            runTest(dispatcher) {
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
                gateway.loginFlow = flow(
                    hostMismatch = NextcloudLoginFlow.HostMismatch("cloud.example.com", "nextcloud"),
                )

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                runCurrent()

                // The note blames an address, so the way out reaches the field
                // that holds it. Nothing was minted or written down, so there is
                // nothing to hand back.
                assertThat(vm.onBackWithin()).isTrue()
                val step = vm.state.value.step as AddAccountStep.EnterAddress
                assertThat(step.input).isEqualTo("https://cloud.example.com/")
                assertThat(record.remembered).isNull()
            }

        @Test
        fun `a clean flow opens without asking`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            runCurrent()

            // Nothing to confirm is not a screen: the common case must not grow
            // a step for the sake of the uncommon one.
            assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.WaitingForBrowser::class.java)
            assertThat(vm.state.value.openInBrowser).isNotNull()
        }
    }

    @Nested
    inner class SurvivingTheProcess {

        @Test
        fun `the flow is written down before the browser is offered the URL`() =
            runTest(dispatcher) {
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("cloud.example.com"),
                )
                gateway.loginFlow = flow()

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                // Not advanceUntilIdle: that would run the whole twenty-minute
                // poll loop out to its expiry, which forgets the flow again.
                runCurrent()

                // ⚠️ The browser is a separate task, so dying while the user
                // approves is ordinary — and the poll token is the only way back
                // to the password the server is about to mint.
                assertThat(record.remembered).isEqualTo(gateway.loginFlow)
                assertThat(vm.state.value.openInBrowser).isNotNull()
            }

        @Test
        fun `a spent flow is not left behind to be polled again`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()
            gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                NextcloudLoginFlow.Credentials(
                    server = "https://cloud.example.com/".toHttpUrl(),
                    loginName = "me",
                    appPassword = "app-pw",
                ),
            )
            gateway.discoveryOutcomes += found(collection("Tasks"))

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()

            // The server deleted the flow row before answering, so the token now
            // buys nothing and a reclaim would poll it for no reason.
            assertThat(record.remembered).isNull()
            assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.ChooseLists::class.java)
        }

        @Test
        fun `an abandoned flow is kept, because the user may still approve it`() =
            runTest(dispatcher) {
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
                gateway.loginFlow = flow()
                gateway.pollResults += NextcloudLoginFlow.PollResult.Expired("window closed")

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()
                vm.onStartOver()
                advanceUntilIdle()

                // ⚠️ We gave up; the browser tab did not. Approving in it still
                // mints a password, and this record is the only thing that can
                // collect it and hand it back — a poll past the deadline is what
                // retires it, not our giving up.
                assertThat(record.remembered).isNotNull()
                assertThat(record.log).containsExactly("remember")
            }
    }

    /**
     * What a killed process leaves behind.
     *
     * The browser is a separate task, so ours is a background process while the
     * user approves and the system is entitled to kill it. A second view model
     * over the same [SavedStateHandle] is exactly that.
     */
    @Nested
    inner class RestoringAfterProcessDeath {

        @Test
        fun `a minted password survives and the lists are re-read`() = runTest(dispatcher) {
            val handle = SavedStateHandle()
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()
            gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                NextcloudLoginFlow.Credentials(
                    server = "https://cloud.example.com/".toHttpUrl(),
                    loginName = "me",
                    appPassword = "app-pw",
                ),
            )
            gateway.discoveryOutcomes += found(collection("Tasks"), collection("Shopping"))

            val first = viewModel(handle)
            first.enterAddress("https://cloud.example.com/")
            first.onAddressSubmitted()
            advanceUntilIdle()
            (first.state.value.step as AddAccountStep.ChooseLists).let { step ->
                first.onListToggled(step.collections.last().url)
            }

            // The process dies here — after approval, holding a one-shot app
            // password the server will never issue again.
            gateway.discoveryOutcomes += found(collection("Tasks"), collection("Shopping"))
            val second = viewModel(handle)
            advanceUntilIdle()

            val step = second.state.value.step as AddAccountStep.ChooseLists
            // Re-read rather than restored: `found` is not saved, so the server
            // answers for it again — with the credential that survived.
            assertThat(gateway.discoveries.last().credentials?.password).isEqualTo("app-pw")
            // And what the user had ticked is still ticked.
            assertThat(step.selected.map { it.toString() })
                .containsExactly("https://cloud.example.com/dav/Tasks/")
        }

        @Test
        fun `a typed password is not written down`() = runTest(dispatcher) {
            val handle = SavedStateHandle()
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.Unauthenticated
            gateway.loginFlow = null

            val first = viewModel(handle)
            first.enterAddress("https://cloud.example.com/")
            first.onAddressSubmitted()
            advanceUntilIdle()
            first.onUsernameChanged("me")
            first.onPasswordChanged("account-password")

            val second = viewModel(handle)
            advanceUntilIdle()

            // ⚠️ The username comes back, the password does not. It is the
            // user's own account password on this route, retyping it costs one
            // field, and saved state is written out by the system.
            val step = second.state.value.step as AddAccountStep.EnterCredentials
            assertThat(step.username).isEqualTo("me")
            assertThat(step.password).isEmpty()
        }

        @Test
        fun `the address that was typed comes back with it`() = runTest(dispatcher) {
            val handle = SavedStateHandle()
            val first = viewModel(handle)
            first.enterAddress("https://cloud.example.com/")

            val step = viewModel(handle).state.value.step as AddAccountStep.EnterAddress
            assertThat(step.input).isEqualTo("https://cloud.example.com/")
            assertThat(step.choice).isEqualTo(ProviderChoice.OtherServer)
        }

        @Test
        fun `a service picked by name comes back as that service`() = runTest(dispatcher) {
            val handle = SavedStateHandle()
            viewModel(handle).onProviderChosen(ProviderChoice.Service(CalDavProvider.FASTMAIL))

            // The errand screen is a step of its own, and it is the one the
            // choice lands on — so restoring the choice has to restore that too.
            val step = viewModel(handle).state.value.step as AddAccountStep.PrepareAccess
            assertThat(step.choice).isEqualTo(ProviderChoice.Service(CalDavProvider.FASTMAIL))
        }

        @Test
        fun `dying at the browser step lands back on the address`() = runTest(dispatcher) {
            val handle = SavedStateHandle()
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = flow()

            val first = viewModel(handle)
            first.enterAddress("https://cloud.example.com/")
            first.onAddressSubmitted()
            runCurrent()

            // Nothing to resume: the flow is out at a browser this process no
            // longer owns, and the persisted record is what deals with it.
            val step = viewModel(handle).state.value.step as AddAccountStep.EnterAddress
            assertThat(step.input).isEqualTo("https://cloud.example.com/")
        }

        @Test
        fun `starting over leaves nothing to restore`() = runTest(dispatcher) {
            val handle = SavedStateHandle()
            val first = viewModel(handle)
            first.enterAddress("https://cloud.example.com/")
            first.onStartOver()

            val step = viewModel(handle).state.value.step as AddAccountStep.ChooseProvider
            assertThat(step.choice).isNull()
        }
    }

    @Nested
    inner class AbandoningTheBrowserFlow {

        @Test
        fun `stepping back from the credentials hands the minted password back`() =
            runTest(dispatcher) {
                val vm = approveThen(
                    CalDavDiscovery.Outcome.NeedsAuthentication(listOf("cloud.example.com")),
                )
                // The credentials step is reachable *from* an approved browser
                // flow, so stepping back off it is one of the routes that leaks a
                // one-shot password if it does not clean up.
                vm.onBrowserCancelled()
                gateway.revoked.clear()
                vm.onBackWithin()
                advanceUntilIdle()

                assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.EnterAddress::class.java)
            }

        private fun TestScope.approveThen(outcome: CalDavDiscovery.Outcome): AddAccountViewModel {
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                listOf("cloud.example.com"),
            )
            gateway.loginFlow = flow()
            gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                NextcloudLoginFlow.Credentials(
                    server = "https://dav.example.com/".toHttpUrl(),
                    loginName = "me",
                    appPassword = "app-pw",
                ),
            )
            gateway.discoveryOutcomes += outcome
            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()
            return vm
        }

        @Test
        fun `a password nobody will use is handed back`() = runTest(dispatcher) {
            val vm = approveThen(
                CalDavDiscovery.Outcome.Failed(
                    CalDavDiscovery.Outcome.Cause.UNREACHABLE,
                    "no route to host",
                ),
            )

            // ⚠️ The path the user actually has. After a post-approval failure
            // the screen shows the address with an error and a *Continue* button
            // — there is no start-over here, so a test that used one would pass
            // against an affordance nobody can reach.
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.Failed(
                CalDavDiscovery.Outcome.Cause.UNREACHABLE,
                "no route to host",
            )
            vm.onAddressSubmitted()
            advanceUntilIdle()

            // Nextcloud hands it over exactly once. Walking away leaves it valid
            // for ever, under the same name as every other attempt — so the user
            // cannot tell which entry their working account uses.
            val handedBack = gateway.revoked.single()
            assertThat(handedBack.password).isEqualTo("app-pw")
            // The server that issued it, not the one the user typed.
            assertThat(handedBack.origin.toString()).isEqualTo("https://dav.example.com/")
        }

        @Test
        fun `an account with no usable lists hands its password back`() = runTest(dispatcher) {
            val vm = approveThen(found())

            // fatal() offers only "start over", so that is the whole exit.
            vm.onStartOver()
            advanceUntilIdle()

            assertThat(gateway.revoked).hasSize(1)
        }

        @Test
        fun `leaving the screen hands the password back`() = runTest(dispatcher) {
            val vm = approveThen(
                CalDavDiscovery.Outcome.Failed(
                    CalDavDiscovery.Outcome.Cause.UNREACHABLE,
                    "no route to host",
                ),
            )

            vm.abandonMintedPassword()
            advanceUntilIdle()

            assertThat(gateway.revoked).hasSize(1)
        }

        @Test
        fun `a saved account keeps its password`() = runTest(dispatcher) {
            val vm = approveThen(found(collection("Tasks")))
            vm.onSave()
            advanceUntilIdle()

            // What the screen does once it is Done.
            vm.onStartOver()
            advanceUntilIdle()

            // ⚠️ The one way this fix can do real harm: revoking the credential
            // of the account just created. It would sync once and then 401 for
            // ever, long after anyone connects it to adding the account.
            assertThat(gateway.revoked).isEmpty()
        }

        @Test
        fun `a typed password after an approval hands the minted one back`() =
            runTest(dispatcher) {
                val vm = approveThen(
                    CalDavDiscovery.Outcome.NeedsAuthentication(listOf("cloud.example.com")),
                )
                // Retry, this time with no login flow on offer, so the user types
                // their own password instead.
                gateway.loginFlow = null
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("cloud.example.com"),
                )
                vm.onAddressSubmitted()
                advanceUntilIdle()

                // The retry itself hands back the first password.
                assertThat(gateway.revoked.map { it.password }).containsExactly("app-pw")
            }

        @Test
        fun `a revoke that fails is never surfaced`() = runTest(dispatcher) {
            gateway.revokeSucceeds = false
            val vm = approveThen(
                CalDavDiscovery.Outcome.Failed(
                    CalDavDiscovery.Outcome.Cause.UNREACHABLE,
                    "no route to host",
                ),
            )

            vm.onStartOver()
            advanceUntilIdle()

            assertThat(vm.state.value.fatal).isNull()
            assertThat(vm.state.value.step).isInstanceOf(AddAccountStep.ChooseProvider::class.java)
        }

        @Test
        fun `a rejected password is not reported as an empty account`() = runTest(dispatcher) {
            val vm = approveThen(
                CalDavDiscovery.Outcome.NeedsAuthentication(listOf("cloud.example.com")),
            )

            // "Signed in, but no task lists" said of a credential the server had
            // just refused. Same registrable domain, so retrying may help.
            val step = vm.state.value.step as AddAccountStep.EnterAddress
            assertThat(step.error).isEqualTo(CalDavDiscovery.Outcome.Cause.SERVER_ERROR)
        }

        @Test
        fun `a home set outside the credential scope is called what it is`() =
            runTest(dispatcher) {
                val vm = approveThen(
                    CalDavDiscovery.Outcome.NeedsAuthentication(listOf("dav.elsewhere.org")),
                )

                // Retrying cannot reach it, and each attempt mints another
                // password — so this is fatal rather than back-to-the-address.
                // Stronger than a substring: it pins the host into the argument
                // rather than anywhere in a sentence, so the translation decides
                // where it goes.
                assertThat(vm.state.value.fatal)
                    .isEqualTo(AddAccountMessage.OutsideCredentialScope("dav.elsewhere.org"))
            }
    }

    @Nested
    inner class TheCredentialsStep {

        @Test
        fun `an address with no parseable root is an error, not a silent retry`() =
            runTest(dispatcher) {
                // serverRootFor cannot parse a host-with-path, and passing null
                // sent no credentials at all — then reported the 401 as
                // "credentials rejected" about a password nothing had tried.
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
                gateway.loginFlow = null

                val vm = viewModel()
                vm.enterAddress("cloud.example.com/nextcloud")
                vm.onAddressSubmitted()
                advanceUntilIdle()
                vm.onUsernameChanged("me")
                vm.onPasswordChanged("pw")
                vm.onCredentialsSubmitted()
                advanceUntilIdle()

                val step = vm.state.value.step as AddAccountStep.EnterAddress
                assertThat(step.error).isEqualTo(CalDavDiscovery.Outcome.Cause.NOT_AN_ADDRESS)
                // One discovery, the anonymous one. Nothing was sent blind.
                assertThat(gateway.discoveries).hasSize(1)
            }

        @Test
        fun `the authenticated pass refreshes the hosts the diagnostic reads`() =
            runTest(dispatcher) {
                // The anonymous probe stops at the root; an authenticated one
                // reaches the principal and its home sets, so the cross-domain
                // home set behind the 401 first appears here.
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
                gateway.loginFlow = null
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("dav.elsewhere.test"),
                )

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()
                vm.onUsernameChanged("me")
                vm.onPasswordChanged("pw")
                vm.onCredentialsSubmitted()
                advanceUntilIdle()

                val step = vm.state.value.step as AddAccountStep.EnterCredentials
                assertThat(step.error)
                    .isEqualTo(AddAccountMessage.OutsideCredentialScope("dav.elsewhere.test"))
            }
    }

    @Nested
    inner class ChoosingLists {

        @Test
        fun `writable lists are pre-ticked and read-only ones are not`() = runTest(dispatcher) {
            gateway.discoveryOutcomes += found(
                collection("Mine"),
                collection("Someone else's", readOnly = true),
            )

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()

            val step = vm.state.value.step as AddAccountStep.ChooseLists
            assertThat(step.selected).hasSize(1)
            assertThat(step.selected.single().toString()).contains("Mine")
        }

        @Test
        fun `an account with no usable lists is a dead end worth explaining`() =
            runTest(dispatcher) {
                gateway.discoveryOutcomes += found()

                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()

                assertThat(vm.state.value.fatal).isEqualTo(AddAccountMessage.NoUsableLists)
            }
    }

    @Nested
    inner class Finishing {

        @Test
        fun `starting over clears the previous account's credentials`() = runTest(dispatcher) {
            // Without this, a second account is created with the first account's
            // username and app password.
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            gateway.loginFlow = null

            val vm = viewModel()
            vm.enterAddress("https://cloud.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()
            vm.onUsernameChanged("me")
            vm.onPasswordChanged("first-pw")

            vm.onStartOver()

            assertThat(vm.state.value).isEqualTo(AddAccountUiState())
            // And a save attempt now has nothing to save, rather than reusing it.
            gateway.discoveryOutcomes += found(collection("Tasks"))
            vm.enterAddress("https://other.example.com/")
            vm.onAddressSubmitted()
            advanceUntilIdle()
            vm.onSave()
            advanceUntilIdle()
            assertThat(creator.created).isEmpty()
            assertThat(vm.state.value.step)
                .isInstanceOf(AddAccountStep.EnterCredentials::class.java)
        }

        @Test
        fun `a new address does not inherit the previous server's credentials`() =
            runTest(dispatcher) {
                // Approve on A, discovery fails afterwards, type B — which
                // answers anonymously. The screen offers *Continue* here, not
                // start over, so nothing else clears what A minted.
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(
                    listOf("cloud.example.com"),
                )
                gateway.loginFlow = flow()
                gateway.pollResults += NextcloudLoginFlow.PollResult.Approved(
                    NextcloudLoginFlow.Credentials(
                        server = "https://cloud.example.com/".toHttpUrl(),
                        loginName = "me",
                        appPassword = "a-pw",
                    ),
                )
                gateway.discoveryOutcomes += CalDavDiscovery.Outcome.Failed(
                    CalDavDiscovery.Outcome.Cause.UNREACHABLE,
                    "no route to host",
                )
                val vm = viewModel()
                vm.enterAddress("https://cloud.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()

                gateway.loginFlow = null
                gateway.discoveryOutcomes += found(collection("Tasks"))
                vm.enterAddress("https://other.example.com/")
                vm.onAddressSubmitted()
                advanceUntilIdle()
                vm.onSave()
                advanceUntilIdle()

                // ⚠️ Otherwise an account named me@other.example.com is created
                // carrying cloud.example.com's app password.
                assertThat(creator.created).isEmpty()
                assertThat(vm.state.value.step)
                    .isInstanceOf(AddAccountStep.EnterCredentials::class.java)
            }

        @Test
        fun `a failure inside create leaves a translatable message, not a spinner`() =
            runTest(dispatcher) {
                creator.thrown = IllegalStateException("UNIQUE constraint failed")

                val vm = signedIn()
                vm.onSave()
                advanceUntilIdle()

                // ⚠️ The exception's words are deliberately gone. They are
                // untranslated and unreadable; the cause is what the user sees,
                // and the detail is kept for logs.
                assertThat(vm.state.value.fatal).isEqualTo(AddAccountMessage.NotSaved)
                assertThat(vm.state.value.step).isNotInstanceOf(AddAccountStep.Working::class.java)
            }

        @Test
        fun `an add refused for External storage says why`() = runTest(dispatcher) {
            creator.outcome = AccountRepository.Outcome.ExternalStorage

            val vm = signedIn()
            vm.onSave()
            advanceUntilIdle()

            assertThat(vm.state.value.fatal).isEqualTo(AddAccountMessage.ExternalStorage)
        }

        @Test
        fun `a completed add reports the account it created`() = runTest(dispatcher) {
            val vm = signedIn()
            vm.onSave()
            advanceUntilIdle()

            assertThat(creator.created).containsExactly("me@cloud.example.com")

            // The receipt, not the exit: the flow says what it built before it
            // hands back, and only the user's acknowledgement ends it.
            val summary = vm.state.value.step as AddAccountStep.Summary
            assertThat(summary.title).isEqualTo("cloud.example.com")
            assertThat(summary.username).isEqualTo("me")
            assertThat(summary.lists).containsExactly("Tasks")

            vm.onSummaryDone()
            assertThat(vm.state.value.step).isEqualTo(AddAccountStep.Done)
        }
    }

    @Nested
    inner class SigningInAgain {

        @Test
        fun `the server and username come prefilled`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.startReauthentication(
                accountId = 9,
                principalUrl = "https://host.example.com/nextcloud/remote.php/dav/principals/users/me/",
                username = "me",
            )

            val address = vm.state.value.step as AddAccountStep.EnterAddress
            assertThat(address.choice).isEqualTo(ProviderChoice.Service(CalDavProvider.NEXTCLOUD))
            // The root, which is where the login flow lives on a subpath install.
            assertThat(address.input).isEqualTo("https://host.example.com/nextcloud")
            assertThat(vm.state.value.reauthenticating).isTrue()

            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            vm.onAddressSubmitted()
            advanceUntilIdle()

            assertThat((vm.state.value.step as AddAccountStep.EnterCredentials).username).isEqualTo("me")
        }

        @Test
        fun `the save is aimed at the same account`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.startReauthentication(9, "https://cloud.example.com/dav/principals/me/", "me")
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            vm.onAddressSubmitted()
            advanceUntilIdle()
            gateway.discoveryOutcomes += found(collection("Tasks"))
            vm.onPasswordChanged("pw")
            vm.onCredentialsSubmitted()
            advanceUntilIdle()

            vm.onSave()
            advanceUntilIdle()

            assertThat(creator.reauthenticated).containsExactly(9L)
        }

        @Test
        fun `starting over forgets the account being signed in to`() = runTest(dispatcher) {
            val vm = viewModel()
            vm.startReauthentication(9, "https://cloud.example.com/dav/principals/me/", "me")
            vm.onStartOver()

            assertThat(vm.state.value.reauthenticating).isFalse()
            vm.enterAddress("https://cloud.example.com/")
            gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
            vm.onAddressSubmitted()
            advanceUntilIdle()
            gateway.discoveryOutcomes += found(collection("Tasks"))
            vm.onUsernameChanged("me")
            vm.onPasswordChanged("pw")
            vm.onCredentialsSubmitted()
            advanceUntilIdle()
            vm.onSave()
            advanceUntilIdle()

            assertThat(creator.reauthenticated).containsExactly(null)
        }

        @Test
        fun `a server without a subpath is its origin`() {
            assertThat(
                AddAccountViewModel.serverAddressOf("https://radicale.example.com/user/".toHttpUrl()),
            ).isEqualTo("https://radicale.example.com")
        }
    }

    /**
     * A ViewModel driven the way the screen drives it, up to the list step:
     * address → credentials → discovery → lists. Credentials are captured on
     * submit, so going straight from the address to a `Found` leaves the
     * username and password empty.
     */
    private fun kotlinx.coroutines.test.TestScope.signedIn(): AddAccountViewModel {
        gateway.discoveryOutcomes += CalDavDiscovery.Outcome.NeedsAuthentication(emptyList())
        gateway.loginFlow = null

        val vm = viewModel()
        vm.enterAddress("https://cloud.example.com/")
        vm.onAddressSubmitted()
        advanceUntilIdle()

        gateway.discoveryOutcomes += found(collection("Tasks"))
        vm.onUsernameChanged("me")
        vm.onPasswordChanged("pw")
        vm.onCredentialsSubmitted()
        advanceUntilIdle()
        return vm
    }

    /**
     * The taps and typing that put a bare address into the flow: step 1's "Other
     * server", then the field. An unnamed server is what a typed address *is*, so
     * every test that only cares about discovery takes this route.
     */
    private fun AddAccountViewModel.enterAddress(input: String) {
        onProviderChosen(ProviderChoice.OtherServer)
        onAddressChanged(input)
    }

    // ------------------------------------------------------------- fixtures

    private fun collection(name: String, readOnly: Boolean = false) = TaskCollection(
        url = "https://cloud.example.com/dav/$name/".toHttpUrl(),
        displayName = name,
        color = null,
        readOnly = readOnly,
        isShared = false,
        supportsSyncCollection = true,
        maxResourceSize = null,
    )

    private fun found(vararg collections: TaskCollection) = CalDavDiscovery.Outcome.Found(
        principal = "https://cloud.example.com/principals/me/".toHttpUrl(),
        collections = collections.toList(),
        homeSets = listOf("https://cloud.example.com/dav/".toHttpUrl()),
        movedTo = null,
        crossHostHomeSets = emptyList(),
    )

    private fun flow(
        loginUrl: String = "https://cloud.example.com/login/flow",
        hostMismatch: NextcloudLoginFlow.HostMismatch? = null,
    ) = NextcloudLoginFlow.Flow(
        loginUrl = loginUrl.toHttpUrl(),
        pollEndpoint = "https://cloud.example.com/login/v2/poll".toHttpUrl(),
        pollToken = "token",
        deadlineEpochSeconds = Long.MAX_VALUE,
        hostMismatch = hostMismatch,
    )

    private class FakeGateway : CalDavGateway {
        data class Call(val target: String, val credentials: CalDavGateway.Credentials?)

        val discoveries = mutableListOf<Call>()
        val discoveryOutcomes = ArrayDeque<CalDavDiscovery.Outcome>()
        val pollResults = ArrayDeque<NextcloudLoginFlow.PollResult>()
        var loginFlow: NextcloudLoginFlow.Flow? = null

        override suspend fun discover(
            target: String,
            credentials: CalDavGateway.Credentials?,
        ): CalDavDiscovery.Outcome {
            discoveries += Call(target, credentials)
            return discoveryOutcomes.removeFirstOrNull()
                ?: CalDavDiscovery.Outcome.Failed(
                    CalDavDiscovery.Outcome.Cause.SERVER_ERROR,
                    "no outcome queued",
                )
        }

        var loginFlowsStarted = 0

        override suspend fun startLoginFlow(server: HttpUrl): NextcloudLoginFlow.Flow? {
            loginFlowsStarted++
            return loginFlow
        }

        override suspend fun pollLoginFlow(flow: NextcloudLoginFlow.Flow) =
            pollResults.removeFirstOrNull() ?: NextcloudLoginFlow.PollResult.Pending

        /** Revocation is best effort and the flow never depends on it. */
        override suspend fun revokeAppPassword(
            credentials: CalDavGateway.Credentials,
        ): Boolean = true

        val revoked = mutableListOf<CalDavGateway.Credentials>()
        var revokeSucceeds = true

        override suspend fun revokeIssuedAppPassword(
            credentials: CalDavGateway.Credentials,
        ): Boolean {
            revoked += credentials
            return revokeSucceeds
        }
    }

    /** Remembers what the flow told it, in the order it was told. */
    private class FakeLoginFlowRecord : LoginFlowRecord {
        val log = mutableListOf<String>()
        var remembered: NextcloudLoginFlow.Flow? = null

        override suspend fun remember(flow: NextcloudLoginFlow.Flow) {
            remembered = flow
            log += "remember"
        }

        override suspend fun forget() {
            remembered = null
            log += "forget"
        }
    }

    private class FakeCreator : AccountCreator {
        val created = mutableListOf<String>()
        val reauthenticated = mutableListOf<Long?>()
        var thrown: Throwable? = null
        var outcome: AccountRepository.Outcome? = null

        override suspend fun create(
            displayName: String,
            username: String,
            appPassword: String,
            found: CalDavDiscovery.Outcome.Found,
            selected: Set<TaskCollection>,
            reauthenticating: Long?,
        ): AccountRepository.Outcome {
            reauthenticated += reauthenticating
            thrown?.let { throw it }
            outcome?.let { return it }
            created += displayName
            return AccountRepository.Outcome.Created(created.size.toLong())
        }
    }
}
