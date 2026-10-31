package com.aiexile.agendula.ui.common

/**
 * The colours offered when creating or editing a task list.
 *
 * Raw ARGB, the way a CalDAV server sends one — every surface that draws a list
 * colour runs it through
 * [de.jeanlucmakiola.floret.components.pastelize] first, so these are hues
 * rather than final fills, chosen to stay distinguishable after that pass. A
 * list can still carry any colour a server gives it; this is only the set the
 * app hands out.
 */
val ListPalette: List<Int> = listOf(
    0xFF7A5C6B.toInt(), // mauve — Agendula's own seed
    0xFFD7484A.toInt(), // red
    0xFFE8743B.toInt(), // orange
    0xFFE0A32E.toInt(), // amber
    0xFF7CA83E.toInt(), // olive
    0xFF35A06A.toInt(), // green
    0xFF19938C.toInt(), // teal
    0xFF2A9BC4.toInt(), // cyan
    0xFF3C74C8.toInt(), // blue
    0xFF6A5CC0.toInt(), // indigo
    0xFF9455B8.toInt(), // purple
    0xFFC94F8E.toInt(), // pink
)

/** What a new list gets before the user picks anything. */
val DefaultListColor: Int = ListPalette.first()
