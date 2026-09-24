package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.TypeConverter
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.statusFromInt
import de.jeanlucmakiola.agendula.domain.toInt
import kotlin.time.Instant

/**
 * Storage encodings for the entity types SQLite has no column type for. Time is
 * epoch millis; [TaskStatus] goes through the `domain` mappers so that numbering
 * keeps its single home.
 *
 * `PRIORITY` deliberately has no converter — it is stored as the raw iCalendar
 * integer, because [de.jeanlucmakiola.agendula.domain.Priority] is a lossy
 * bucketing and a converter would apply it before the value reaches disk.
 */
object Converters {

    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun instantFromMillis(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun statusToInt(value: TaskStatus): Int = value.toInt()

    @TypeConverter
    fun statusFrom(value: Int): TaskStatus = statusFromInt(value)

    @TypeConverter
    fun alarmReferenceToString(value: AlarmReference): String = value.name

    @TypeConverter
    fun alarmReferenceFrom(value: String): AlarmReference =
        runCatching { AlarmReference.valueOf(value) }.getOrDefault(AlarmReference.DUE)
}
