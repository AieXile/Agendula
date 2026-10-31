package com.aiexile.agendula.data.tasks.room

import androidx.room.TypeConverter
import com.aiexile.agendula.domain.TaskStatus
import com.aiexile.agendula.domain.ical.ICalValues
import com.aiexile.agendula.domain.normalizeTags
import com.aiexile.agendula.domain.statusFromInt
import com.aiexile.agendula.domain.toInt
import kotlin.time.Instant

/**
 * Storage encodings for the entity types SQLite has no column type for. Time is
 * epoch millis; [TaskStatus] goes through the `domain` mappers so that numbering
 * keeps its single home.
 *
 * `PRIORITY` deliberately has no converter — it is stored as the raw iCalendar
 * integer, because [com.aiexile.agendula.domain.Priority] is a lossy
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

/**
 * [TaskEntity.categories] stored as a `CATEGORIES` value, so escaping has one
 * home; empty when there are none. Scoped to that field rather than the
 * database, so no other `List<String>` picks up iCalendar escaping by accident.
 */
object CategoriesConverter {
    @TypeConverter
    fun toText(value: List<String>): String = ICalValues.formatTextList(normalizeTags(value))

    @TypeConverter
    fun fromText(value: String): List<String> =
        if (value.isEmpty()) emptyList() else normalizeTags(ICalValues.parseTextList(value))
}
