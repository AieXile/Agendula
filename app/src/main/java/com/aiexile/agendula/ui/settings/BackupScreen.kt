package com.aiexile.agendula.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aiexile.agendula.R
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position

/** Backup & restore: the way into export and import, as its own entry (after Calendula). */
@Composable
internal fun BackupScreen(
    onOpenExport: () -> Unit,
    onOpenImport: () -> Unit,
    onBack: () -> Unit,
    visible: Boolean,
) {
    CollapsingScaffold(
        title = stringResource(R.string.settings_section_backup),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        GroupedRow(
            title = stringResource(R.string.settings_export),
            summary = stringResource(R.string.settings_export_hint),
            position = Position.Top,
            onClick = onOpenExport,
        )
        GroupedRow(
            title = stringResource(R.string.import_title),
            summary = stringResource(R.string.settings_import_hint),
            position = Position.Bottom,
            onClick = onOpenImport,
        )
    }
}
