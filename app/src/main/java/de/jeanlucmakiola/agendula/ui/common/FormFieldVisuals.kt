package de.jeanlucmakiola.agendula.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.ui.graphics.vector.ImageVector
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.TaskFormField

/** The label an optional edit-form section goes by, in the form and in Settings. */
fun TaskFormField.labelRes(): Int = when (this) {
    TaskFormField.Description -> R.string.field_description
    TaskFormField.Recurrence -> R.string.edit_recurrence_label
    TaskFormField.Priority -> R.string.edit_priority_label
    TaskFormField.Progress -> R.string.edit_progress_label
    TaskFormField.Parent -> R.string.edit_parent_label
    TaskFormField.Reminder -> R.string.edit_reminder_label
    TaskFormField.Location -> R.string.edit_location_label
    TaskFormField.Url -> R.string.edit_url_label
    TaskFormField.Tags -> R.string.edit_tags_label
}

fun TaskFormField.icon(): ImageVector = when (this) {
    TaskFormField.Description -> Icons.AutoMirrored.Rounded.Notes
    TaskFormField.Recurrence -> Icons.Rounded.Repeat
    TaskFormField.Priority -> Icons.Rounded.Flag
    TaskFormField.Progress -> Icons.Rounded.Percent
    TaskFormField.Parent -> Icons.Rounded.AccountTree
    TaskFormField.Reminder -> Icons.Rounded.Notifications
    TaskFormField.Location -> Icons.Rounded.LocationOn
    TaskFormField.Url -> Icons.Rounded.Link
    TaskFormField.Tags -> Icons.Rounded.Sell
}
