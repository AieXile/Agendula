package de.jeanlucmakiola.agendula.domain.recurrence

/** How far a write to one occurrence of a recurring task reaches. */
enum class RecurringScope { ThisOccurrence, ThisAndFollowing, AllOccurrences }
