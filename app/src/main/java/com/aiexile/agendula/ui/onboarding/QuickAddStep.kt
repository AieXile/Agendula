package com.aiexile.agendula.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.tasklist.TaskListPreview
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OnboardingSpace
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.ViewPreviewFrame
import de.jeanlucmakiola.floret.components.positionOf

/**
 * The last step: how a list offers to add a task — the bar pinned to the bottom,
 * or the floating button. Built like the family's other preview pickers: a live
 * task list over connected grouped rows, where picking applies immediately and
 * the preview is the confirmation, so the answer is given by looking rather than
 * by imagining.
 */
@Composable
internal fun QuickAddStep(
    chrome: StepChrome,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    val options = listOf(true, false)

    StepScaffold(
        chrome = chrome,
        // Full-bleed: the preview plate and the rows carry the inset themselves.
        contentPadding = 0.dp,
        topSpacing = OnboardingSpace.xs,
        // The preview gets the room it needs; Continue scrolls in under the
        // options rather than pinning to the bottom.
        scrollingActions = true,
        hero = {
            Text(
                text = stringResource(R.string.onboarding_quick_add_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = GroupedListInset),
            )
        },
        actions = {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .padding(horizontal = GroupedListInset)
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_quick_add_done),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) {
        ViewPreviewFrame(
            selected = enabled,
            label = "quick-add-preview",
            modifier = Modifier.padding(top = OnboardingSpace.xs, bottom = OnboardingSpace.md),
        ) { bottomAddBar ->
            TaskListPreview(bottomAddBar = bottomAddBar, height = PreviewHeight)
        }

        options.forEachIndexed { index, option ->
            val isSelected = option == enabled
            GroupedRow(
                title = stringResource(
                    if (option) R.string.onboarding_quick_add_bar else R.string.onboarding_quick_add_button,
                ),
                summary = stringResource(
                    if (option) {
                        R.string.onboarding_quick_add_bar_hint
                    } else {
                        R.string.onboarding_quick_add_button_hint
                    },
                ),
                position = positionOf(index, options.size),
                selected = isSelected,
                trailing = if (isSelected) {
                    { SelectedCheck() }
                } else {
                    null
                },
                onClick = { onToggle(option) },
            )
        }
    }
}

/** The size the family's other preview pickers give it. */
private val PreviewHeight: Dp = 280.dp
