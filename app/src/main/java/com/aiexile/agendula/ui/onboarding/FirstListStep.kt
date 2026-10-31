package com.aiexile.agendula.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.common.DefaultListColor
import com.aiexile.agendula.ui.lists.ListColorGrid
import com.aiexile.agendula.ui.lists.ListNameField
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.OnboardingSpace

/**
 * Somewhere to put a task, so first run does not end on an empty screen. Only
 * reached when the user has no lists at all, so there is no way past it — the
 * name and colour come prefilled and the button is one tap.
 *
 * The list is always a device-local one, even though the app can create lists on
 * a server (`RemoteListRepository`): this step stays one tap, and a synced user
 * who lands here (an account whose server held no task collections) can make a
 * server list from the lists screen afterwards.
 */
@Composable
internal fun FirstListStep(chrome: StepChrome, onCreate: (name: String, color: Int) -> Unit) {
    val suggested = stringResource(R.string.onboarding_list_default_name)
    var name by rememberSaveable { mutableStateOf(suggested) }
    var color by rememberSaveable { mutableIntStateOf(DefaultListColor) }
    val commit = { if (name.isNotBlank()) onCreate(name, color) }

    StepScaffold(
        chrome = chrome,
        hero = { SquircleHero(Icons.Rounded.Folder) },
        // The field and the palette carry their own inset.
        contentPadding = 0.dp,
        actions = {
            Button(
                onClick = commit,
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_list_create),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) {
        StepHeader(
            title = stringResource(R.string.onboarding_list_title),
            body = stringResource(R.string.onboarding_list_body),
            horizontalPadding = GroupedListInset,
        )

        Spacer(Modifier.height(OnboardingSpace.lg))

        // Prefilled and not focused: the step is a one-tap accept by default, and
        // raising the keyboard over the palette would hide the other half of it.
        ListNameField(
            name = name,
            color = color,
            autoFocus = false,
            onNameChange = { name = it },
            onImeAction = { commit() },
        )
        Spacer(Modifier.height(20.dp))
        ListColorGrid(selected = color, onSelect = { color = it })
    }
}
