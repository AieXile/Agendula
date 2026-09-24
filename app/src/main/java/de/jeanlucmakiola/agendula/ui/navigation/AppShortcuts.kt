package de.jeanlucmakiola.agendula.ui.navigation

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.jeanlucmakiola.agendula.MainActivity
import de.jeanlucmakiola.agendula.R

/**
 * The launcher's long-press shortcuts. Published at runtime rather than from a
 * static `shortcuts.xml`, whose intents would have to name the release package
 * and so break in the suffixed debug and releaseTest builds — and so the labels
 * follow the app language.
 */
object AppShortcuts {

    fun publish(context: Context) {
        val shortcuts = listOf(
            ShortcutInfoCompat.Builder(context, "new_task")
                .setShortLabel(context.getString(R.string.shortcut_new_task))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_new_task))
                .setIntent(MainActivity.newTaskIntent(context))
                .build(),
            ShortcutInfoCompat.Builder(context, "today")
                .setShortLabel(context.getString(R.string.shortcut_today))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_today))
                .setIntent(MainActivity.todayIntent(context))
                .build(),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
