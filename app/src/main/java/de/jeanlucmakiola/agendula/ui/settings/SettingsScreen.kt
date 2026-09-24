package de.jeanlucmakiola.agendula.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.ui.accounts.AccountDetailScreen
import de.jeanlucmakiola.agendula.ui.accounts.AccountsScreen
import de.jeanlucmakiola.agendula.ui.accounts.AccountsViewModel
import de.jeanlucmakiola.agendula.data.tasks.room.AccountEntity
import de.jeanlucmakiola.agendula.ui.accounts.add.AddAccountScreen
import de.jeanlucmakiola.agendula.ui.accounts.add.AddAccountViewModel
import de.jeanlucmakiola.agendula.ui.export.ExportScreen
import de.jeanlucmakiola.agendula.ui.imports.ImportScreen
import de.jeanlucmakiola.agendula.ui.licences.LicencesScreen
import de.jeanlucmakiola.floret.components.AboutCard
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.crash.CrashReportDialog
import de.jeanlucmakiola.floret.crash.CrashReporter
import de.jeanlucmakiola.floret.crash.openIssueTracker
import de.jeanlucmakiola.floret.crash.submitCrashReport
import de.jeanlucmakiola.floret.locale.AppLanguage

/** The settings sub-screens reached from the hub's category rows. */
private enum class SettingsSection {
    Appearance,
    TaskForm,
    Reminders,
    Storage,
    Export,
    Import,
    Accounts,
    AddAccount,
    Account,
    Licences,
    ;

    /** Where back goes: Export is opened from Storage, the account screens from Accounts. */
    val parent: SettingsSection?
        get() = when (this) {
            Export, Import -> Storage
            AddAccount, Account -> Accounts
            else -> null
        }
}

/**
 * Settings, structured (after Calendula) as a category hub with sliding
 * sub-screens. The hub and the sub-screens share [CollapsingScaffold] and the
 * grouped-row card system; option lists use the full-screen [OptionPicker].
 * A full-screen destination; [onBack] pops it. [initialSection] names a
 * sub-screen to open on (see [de.jeanlucmakiola.agendula.ui.navigation.Dest.Settings]).
 *
 * Every sub-screen is its own file. This one owns the hub, the section enum and
 * the back rules between them, and nothing else.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSection: String? = null,
    initialAccountId: Long? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable(initialAccountId, initialSection) {
        mutableStateOf(
            if (initialAccountId != null) SettingsSection.Account
            else SettingsSection.entries.firstOrNull { it.name == initialSection },
        )
    }
    // Hoisted so the add flow can refresh the list it returns to.
    val accountsViewModel: AccountsViewModel = hiltViewModel()
    // Shared with the add flow's own lookup, so "sign in again" can prefill it.
    val addAccountViewModel: AddAccountViewModel = hiltViewModel()
    val signInAgain: (AccountEntity) -> Unit = { account ->
        addAccountViewModel.startReauthentication(account.id, account.principalUrl, account.username)
        section = SettingsSection.AddAccount
    }
    // Which account the detail screen is showing; the section alone cannot say.
    var openAccount by rememberSaveable(initialAccountId) { mutableStateOf(initialAccountId) }

    // Inside a sub-screen, system back (button or gesture) returns to the hub
    // rather than popping the whole Settings destination to the lists overview.
    BackHandler(enabled = section != null) { section = section?.parent }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        SettingsHub(onBack = onBack, onOpenSection = { section = it })

        SlideInSection(visible = section == SettingsSection.Appearance) {
            AppearanceScreen(state = state, viewModel = viewModel, onBack = { section = null })
        }
        SlideInSection(visible = section == SettingsSection.TaskForm) {
            TaskFormScreen(state = state, viewModel = viewModel, onBack = { section = null })
        }
        SlideInSection(visible = section == SettingsSection.Reminders) {
            RemindersScreen(state = state, viewModel = viewModel, onBack = { section = null })
        }
        // Storage stays composed under Export, so the deeper screen slides over it.
        val storageOpen = section == SettingsSection.Storage ||
            section?.parent == SettingsSection.Storage
        SlideInSection(visible = storageOpen) {
            StorageScreen(
                viewModel = viewModel,
                onOpenExport = { section = SettingsSection.Export },
                onOpenImport = { section = SettingsSection.Import },
                onBack = { section = null },
            )
        }
        SlideInSection(visible = section == SettingsSection.Export) {
            ExportScreen(onBack = { section = SettingsSection.Storage })
        }
        SlideInSection(visible = section == SettingsSection.Import) {
            ImportScreen(onBack = { section = SettingsSection.Storage })
        }
        // Accounts stays composed under Add account, for the same reason Storage
        // stays composed under Export: the deeper screen slides over it.
        val accountsOpen = section == SettingsSection.Accounts ||
            section?.parent == SettingsSection.Accounts
        SlideInSection(visible = accountsOpen) {
            AccountsScreen(
                onAddAccount = {
                    // A sign-in abandoned half-way must not turn this into one.
                    if (addAccountViewModel.state.value.reauthenticating) addAccountViewModel.onStartOver()
                    section = SettingsSection.AddAccount
                },
                onSignInAgain = signInAgain,
                onOpenAccount = {
                    openAccount = it
                    section = SettingsSection.Account
                },
                onOpenStorage = { section = SettingsSection.Storage },
                onBack = { section = null },
                viewModel = accountsViewModel,
            )
        }
        SlideInSection(visible = section == SettingsSection.Licences) {
            LicencesScreen(onBack = { section = null })
        }
        SlideInSection(visible = section == SettingsSection.Account) {
            openAccount?.let { id ->
                AccountDetailScreen(
                    accountId = id,
                    onBack = { section = SettingsSection.Accounts },
                    onRemoved = { section = SettingsSection.Accounts },
                    onSignInAgain = signInAgain,
                    onOpenStorage = { section = SettingsSection.Storage },
                    viewModel = accountsViewModel,
                )
            }
        }
        SlideInSection(visible = section == SettingsSection.AddAccount) {
            AddAccountScreen(
                // The list stays composed underneath, so nothing re-runs its
                // init and OnResume never fires on a section change. It no longer
                // needs to: the accounts come from an observed query, so a new
                // account — and every later sync — arrives on its own.
                onDone = { section = SettingsSection.Accounts },
                onBack = { section = SettingsSection.Accounts },
                viewModel = addAccountViewModel,
            )
        }
    }
}

@Composable
private fun SlideInSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) { content() }
}

// ---------------------------------------------------------------------------
// Hub
// ---------------------------------------------------------------------------

/**
 * Four named groups of category rows, after Calendula's hub: look & behaviour,
 * data, app, and a foot of reference links. Source, licence and privacy sit in
 * that last group rather than as buttons inside the About card — they are
 * reference material, and the card is the app's identity.
 */
@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
) {
    CollapsingScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        // Card and support row are one grouped block, so the call to action
        // continues the container instead of sitting inside it as a button.
        Box(Modifier.padding(horizontal = GroupedListInset)) { AboutCard() }
        SupportRow()
        Spacer(Modifier.height(16.dp))

        // Each group cycles its chip accents so no two rows in it look alike
        // (see [ChipAccent]).
        GroupedSectionHeader(stringResource(R.string.settings_group_look))
        GroupedRow(
            title = stringResource(R.string.settings_section_appearance),
            summary = stringResource(R.string.settings_appearance_subtitle),
            position = Position.Top,
            leading = { CategoryIcon(Icons.Default.Palette, ChipAccent.Secondary) },
            onClick = { onOpenSection(SettingsSection.Appearance) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_section_task_form),
            summary = stringResource(R.string.settings_task_form_subtitle),
            position = Position.Middle,
            leading = { CategoryIcon(Icons.Default.Tune, ChipAccent.Tertiary) },
            onClick = { onOpenSection(SettingsSection.TaskForm) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_section_reminders),
            summary = stringResource(R.string.settings_reminders_subtitle),
            position = Position.Bottom,
            leading = { CategoryIcon(Icons.Default.Notifications, ChipAccent.Primary) },
            onClick = { onOpenSection(SettingsSection.Reminders) },
        )

        Spacer(Modifier.height(8.dp))
        GroupedSectionHeader(stringResource(R.string.settings_group_data))
        GroupedRow(
            title = stringResource(R.string.settings_section_accounts),
            summary = stringResource(R.string.settings_accounts_subtitle),
            position = Position.Top,
            leading = { CategoryIcon(Icons.Rounded.CloudSync, ChipAccent.Tertiary) },
            onClick = { onOpenSection(SettingsSection.Accounts) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_section_storage),
            summary = stringResource(R.string.settings_storage_subtitle),
            position = Position.Bottom,
            leading = { CategoryIcon(Icons.Rounded.Storage, ChipAccent.Secondary) },
            onClick = { onOpenSection(SettingsSection.Storage) },
        )

        Spacer(Modifier.height(8.dp))
        GroupedSectionHeader(stringResource(R.string.settings_group_app))
        LanguageRow(position = Position.Top)
        ReportProblemRow(position = Position.Bottom)

        // Source, licence and privacy sit at the bottom as reference material
        // rather than inside the About card.
        Spacer(Modifier.height(8.dp))
        GroupedSectionHeader(stringResource(R.string.settings_group_about))
        AboutLinkRow(
            label = stringResource(R.string.settings_about_source),
            url = stringResource(R.string.about_source_url),
            icon = ImageVector.vectorResource(R.drawable.ic_codeberg),
            position = Position.Top,
        )
        AboutLinkRow(
            label = stringResource(R.string.settings_license),
            url = stringResource(R.string.about_license_url),
            icon = Icons.Default.Gavel,
            position = Position.Middle,
        )
        GroupedRow(
            title = stringResource(R.string.settings_licences),
            summary = stringResource(R.string.settings_licences_subtitle),
            position = Position.Middle,
            leading = { CategoryIcon(Icons.Default.Code, ChipAccent.Neutral) },
            onClick = { onOpenSection(SettingsSection.Licences) },
        )
        AboutLinkRow(
            label = stringResource(R.string.settings_privacy),
            url = stringResource(R.string.about_privacy_url),
            icon = Icons.Default.PrivacyTip,
            position = Position.Bottom,
        )

        AppVersionText()
    }
}

@Composable
private fun AboutCard() {
    // Layout lives in floret-kit (components.AboutCard); Agendula supplies its
    // own logo and author. Support is the [SupportRow] joined below.
    AboutCard(
        logo = { AppLogo() },
        appName = stringResource(R.string.app_name),
        author = stringResource(R.string.settings_about_author),
        primaryLinks = emptyList(),
        position = Position.Top,
    )
}

/** "Support development", as the closing row of the About card's group. */
@Composable
private fun SupportRow() {
    val context = LocalContext.current
    val url = stringResource(R.string.about_support_url)
    GroupedRow(
        title = stringResource(R.string.settings_about_support),
        position = Position.Bottom,
        leading = { CategoryIcon(Icons.Default.Favorite, ChipAccent.Primary) },
        onClick = { openUrl(context, url) },
    )
}

/**
 * One reference link at the foot of the hub (source, licence, privacy).
 *
 * ⚠️ The privacy policy is linked **in the app**, not only in the Play Console.
 * Play requires both, and the in-app link is the half that is routinely missed.
 * Agendula's data-safety declaration is *Collected, not Shared*, encrypted in
 * transit: Play defines collection as transmitting off-device irrespective of
 * who receives it, so "not collected" is not defensible for a client that PUTs
 * the user's tasks to their own server.
 */
@Composable
private fun AboutLinkRow(label: String, url: String, icon: ImageVector, position: Position) {
    val context = LocalContext.current
    GroupedRow(
        title = label,
        position = position,
        leading = { CategoryIcon(icon, ChipAccent.Neutral) },
        onClick = { openUrl(context, url) },
    )
}

/**
 * The app-language row. Deliberately not floret-kit's `LanguagePickerRow`: the
 * picker it opens carries a "Help translate" header, and inviting contributions
 * right where a user goes looking for their language is app-specific framing,
 * not a family primitive. Everything else matches that recipe.
 */
@Composable
private fun LanguageRow(position: Position) {
    val context = LocalContext.current
    val supported = remember { AppLanguage.supportedTags(context, R.xml.locales_config) }
    // Setting a locale recreates the activity; mirror the choice locally so the
    // row updates instantly even before the recreation lands.
    var current by remember { mutableStateOf(AppLanguage.currentTag(supported)) }
    var showDialog by remember { mutableStateOf(false) }

    // null = follow the system; the rest are BCP-47 tags from locales_config.xml.
    val options = remember(supported) { listOf<String?>(null) + supported }

    GroupedRow(
        title = stringResource(R.string.settings_language),
        summary = languageLabel(current),
        position = position,
        leading = { CategoryIcon(Icons.Default.Language, ChipAccent.Secondary) },
        onClick = { showDialog = true },
    )

    if (showDialog) {
        OptionPicker(
            title = stringResource(R.string.settings_language),
            predictiveBack = true,
            options = options,
            selected = current,
            label = { languageLabel(it) },
            onSelect = {
                current = it
                AppLanguage.apply(it)
            },
            onDismiss = { showDialog = false },
            // Invite contributions right where users pick their language.
            header = {
                val translateUrl = stringResource(R.string.about_translate_url)
                GroupedRow(
                    title = stringResource(R.string.settings_translate),
                    summary = stringResource(R.string.settings_translate_hint),
                    position = Position.Alone,
                    leading = { CategoryIcon(Icons.Default.Translate, ChipAccent.Neutral) },
                    onClick = { openUrl(context, translateUrl) },
                )
                Spacer(Modifier.height(16.dp))
            },
        )
    }
}

@Composable
private fun languageLabel(tag: String?): String =
    if (tag == null) stringResource(R.string.settings_language_auto) else AppLanguage.displayName(tag)

/**
 * Opens the project's issue tracker to report a problem. If a crash report was
 * captured (and not yet sent), it surfaces that report first via the same
 * dialog the next-launch prompt uses; otherwise it opens the issue template
 * chooser. No data leaves the device until the user submits the issue.
 */
@Composable
private fun ReportProblemRow(position: Position) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }

    GroupedRow(
        title = stringResource(R.string.settings_report_problem),
        summary = stringResource(R.string.settings_report_problem_hint),
        position = position,
        leading = { CategoryIcon(Icons.Default.BugReport, ChipAccent.Neutral) },
        onClick = {
            val pending = CrashReporter.pendingReport(context)
            if (pending != null) report = pending else openIssueTracker(context)
        },
    )

    report?.let { pending ->
        CrashReportDialog(
            report = pending,
            onSend = {
                submitCrashReport(context, pending)
                CrashReporter.clearReport(context)
                report = null
            },
            onDismiss = { report = null },
        )
    }
}
