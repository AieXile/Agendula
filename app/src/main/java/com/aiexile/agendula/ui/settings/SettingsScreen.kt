package com.aiexile.agendula.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.BuildConfig
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.export.ExportScreen
import com.aiexile.agendula.ui.imports.ImportScreen
import com.aiexile.agendula.ui.licences.LicencesScreen
import com.aiexile.agendula.ui.navigation.rememberNavTransitions
import de.jeanlucmakiola.floret.components.AboutCard
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.LanguagePickerRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.crash.CrashReportDialog
import de.jeanlucmakiola.floret.crash.CrashReporter
import de.jeanlucmakiola.floret.crash.openIssueTracker
import de.jeanlucmakiola.floret.crash.submitCrashReport

/** The settings sub-screens reached from the hub's category rows. */
internal enum class SettingsSection {
    Appearance,
    TaskForm,
    Notifications,
    Storage,
    Backup,
    Export,
    Import,
    Accounts,
    AddAccount,
    Account,
    Licences,
    ;

    /** The section that stays composed underneath: Backup under export and import, Accounts under its screens. */
    val parent: SettingsSection?
        get() = when (this) {
            Export, Import -> Backup
            AddAccount, Account -> Accounts
            else -> null
        }
}

/**
 * Settings, structured (after Calendula) as a category hub with sliding
 * sub-screens. The hub and the sub-screens share [CollapsingScaffold] and the
 * grouped-row card system; option lists use the full-screen [OptionPicker].
 * A full-screen destination; [onBack] pops it. [initialSection] names a
 * sub-screen to open on (see [com.aiexile.agendula.ui.navigation.Dest.Settings]).
 *
 * Every sub-screen is its own file and handles its own back through the
 * predictive-back preview. This one owns the hub, the section enum and the
 * routing between them, and nothing else.
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
    // Import's "open storage" comes back to Import rather than the hub.
    var storageReturn by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        SettingsHub(onBack = onBack, onOpenSection = { section = it })

        val appearance = section == SettingsSection.Appearance
        SlideInSection(visible = appearance) {
            AppearanceScreen(state = state, viewModel = viewModel, onBack = { section = null }, visible = appearance)
        }
        val taskForm = section == SettingsSection.TaskForm
        SlideInSection(visible = taskForm) {
            TaskFormScreen(state = state, viewModel = viewModel, onBack = { section = null }, visible = taskForm)
        }
        val notifications = section == SettingsSection.Notifications
        SlideInSection(visible = notifications) {
            NotificationsScreen(state = state, viewModel = viewModel, onBack = { section = null }, visible = notifications)
        }
        val storage = section == SettingsSection.Storage
        SlideInSection(visible = storage) {
            StorageScreen(
                viewModel = viewModel,
                onBack = {
                    section = storageReturn
                    storageReturn = null
                },
                visible = storage,
            )
        }
        // Backup stays composed under export and import, so the deeper screen slides over it.
        val backup = section == SettingsSection.Backup || section?.parent == SettingsSection.Backup
        SlideInSection(visible = backup) {
            BackupScreen(
                onOpenExport = { section = SettingsSection.Export },
                onOpenImport = { section = SettingsSection.Import },
                onBack = { section = null },
                visible = backup,
            )
        }
        val exportOpen = section == SettingsSection.Export
        SlideInSection(visible = exportOpen) {
            ExportScreen(onBack = { section = SettingsSection.Backup }, visible = exportOpen)
        }
        val importOpen = section == SettingsSection.Import
        SlideInSection(visible = importOpen) {
            ImportScreen(
                onBack = { section = SettingsSection.Backup },
                onOpenStorage = {
                    storageReturn = SettingsSection.Import
                    section = SettingsSection.Storage
                },
                predictiveBack = true,
                visible = importOpen,
            )
        }
        val licences = section == SettingsSection.Licences
        SlideInSection(visible = licences) {
            LicencesScreen(onBack = { section = null }, visible = licences)
        }
        AccountSections(
            section = section,
            initialAccountId = initialAccountId,
            onSection = { section = it },
        )
    }
}

/**
 * A sub-screen layered over the hub, with the app's secondary-page motion (see
 * [rememberNavTransitions]): it slides in from the right over the hub and, on
 * the way back, slides out the same way instead of fading in place. A close
 * through the predictive-back gesture keeps the surface's own preview
 * underneath the slide.
 */
@Composable
internal fun SlideInSection(visible: Boolean, content: @Composable () -> Unit) {
    val transitions = rememberNavTransitions()
    AnimatedVisibility(visible = visible, enter = transitions.enter, exit = transitions.popExit) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Hub
// ---------------------------------------------------------------------------

/**
 * Four named groups of category rows, after Calendula's hub: look & behaviour,
 * data, app, and a foot of reference links. Source and privacy sit in that last
 * group alongside the open-source notices rather than as buttons inside the
 * About card — they are reference material, and the card is the app's identity.
 */
@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
) {
    CollapsingScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        // Card and support row are one grouped block, so the call to action
        // continues the container instead of sitting inside it as a button.
        // Without a support URL (a fork that has no donation page) the card is
        // the whole block and closes itself.
        val showSupport = stringResource(R.string.about_support_url).isNotBlank()
        Box(Modifier.padding(horizontal = GroupedListInset)) { AboutCard(showSupport) }
        if (showSupport) SupportRow()
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
            onClick = { onOpenSection(SettingsSection.Notifications) },
        )

        Spacer(Modifier.height(8.dp))
        GroupedSectionHeader(stringResource(R.string.settings_group_data))
        if (BuildConfig.SYNC_ENABLED) {
            GroupedRow(
                title = stringResource(R.string.settings_section_accounts),
                summary = stringResource(R.string.settings_accounts_subtitle),
                position = Position.Top,
                leading = { CategoryIcon(Icons.Rounded.CloudSync, ChipAccent.Tertiary) },
                onClick = { onOpenSection(SettingsSection.Accounts) },
            )
        }
        GroupedRow(
            title = stringResource(R.string.settings_section_storage),
            summary = stringResource(R.string.settings_storage_subtitle),
            position = if (BuildConfig.SYNC_ENABLED) Position.Middle else Position.Top,
            leading = { CategoryIcon(Icons.Rounded.Storage, ChipAccent.Primary) },
            onClick = { onOpenSection(SettingsSection.Storage) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_section_backup),
            summary = stringResource(R.string.settings_backup_subtitle),
            position = Position.Bottom,
            leading = { CategoryIcon(Icons.Default.Backup, ChipAccent.Secondary) },
            onClick = { onOpenSection(SettingsSection.Backup) },
        )

        Spacer(Modifier.height(8.dp))
        GroupedSectionHeader(stringResource(R.string.settings_group_app))
        LanguagePickerRow(
            position = Position.Top,
            title = stringResource(R.string.settings_language),
            autoLabel = stringResource(R.string.settings_language_auto),
            localesConfig = R.xml.locales_config,
            predictiveBack = true,
            // Invite contributions right where users pick their language.
            header = {
                val context = LocalContext.current
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
            leading = { CategoryIcon(Icons.Default.Language, ChipAccent.Secondary) },
        )
        ReportProblemRow(position = Position.Bottom)

        // Source and privacy sit at the bottom as reference material rather than
        // inside the About card; the open-source notices have their own screen.
        Spacer(Modifier.height(8.dp))
        GroupedSectionHeader(stringResource(R.string.settings_group_about))
        AboutLinkRow(
            label = stringResource(R.string.settings_about_source),
            url = stringResource(R.string.about_source_url),
            icon = Icons.Default.Code,
            position = Position.Top,
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
private fun AboutCard(showSupport: Boolean) {
    // Layout lives in floret-kit (components.AboutCard); Agendula supplies its
    // own logo and author. Support is the [SupportRow] joined below.
    AboutCard(
        logo = { AppLogo() },
        appName = stringResource(R.string.app_name),
        author = stringResource(R.string.settings_about_author),
        primaryLinks = emptyList(),
        position = if (showSupport) Position.Top else Position.Alone,
    )
}

/** "Support development", as the closing row of the About card's group. */
@Composable
private fun SupportRow() {
    val context = LocalContext.current
    val url = stringResource(R.string.about_support_url)
    if (url.isBlank()) return
    GroupedRow(
        title = stringResource(R.string.settings_about_support),
        position = Position.Bottom,
        leading = { CategoryIcon(Icons.Default.Favorite, ChipAccent.Primary) },
        onClick = { openUrl(context, url) },
    )
}

/**
 * One reference link at the foot of the hub (source, privacy).
 *
 * The privacy policy is linked **in the app** as well as from the repository:
 * a user should be able to read it without leaving the app to find it. If this
 * build is ever listed in a store, that store's declaration must match the same
 * text — "collected, not shared", encrypted in transit, because the tasks are
 * PUT to a server the user chose.
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
