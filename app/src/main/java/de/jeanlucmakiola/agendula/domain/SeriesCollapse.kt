package de.jeanlucmakiola.agendula.domain

/**
 * What a task list shows of a recurring series: one open occurrence — the most
 * recent one that is still due, else the next — plus the occurrences someone
 * actually closed. Every other generated occurrence stays out; a daily series
 * would otherwise fill Overdue with a year of rows and Upcoming with two more.
 */
object SeriesCollapse {

    fun visible(tasks: List<Task>): List<Task> {
        val (series, plain) = tasks.partition { it.isOccurrence }
        val kept = series.groupBy { it.seriesId }.values.flatMap(::pick)
        return plain + kept
    }

    private fun pick(occurrences: List<Task>): List<Task> {
        val ordered = occurrences.sortedBy { it.occurrenceStart }
        val open = ordered.filter { !it.isClosed }
        val current = open.firstOrNull { (it.distanceFromCurrent ?: 0) >= -1 } ?: open.lastOrNull()
        // A closed override is a real completion worth listing; a closed generated
        // occurrence only means the whole series is closed, which one row says.
        val closed = ordered.filter { it.isClosed && it.taskId != it.seriesId }
        val seriesClosed = open.isEmpty() && closed.isEmpty()
        val fallback = if (seriesClosed) ordered.firstOrNull { it.distanceFromCurrent == 0 } ?: ordered.lastOrNull() else null
        return closed + listOfNotNull(current ?: fallback)
    }
}
