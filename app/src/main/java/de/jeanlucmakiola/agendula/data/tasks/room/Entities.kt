package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import de.jeanlucmakiola.agendula.domain.PRIORITY_NONE
import de.jeanlucmakiola.agendula.domain.TaskStatus
import kotlin.time.Instant

/**
 * A CalDAV account. Empty until an account is added, but the FK
 * from [TaskListEntity] exists from v1 so turning sync on never needs a
 * migration. The app password is never stored here — Keystore only.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "principal_url") val principalUrl: String? = null,
    @ColumnInfo(name = "home_set_url") val homeSetUrl: String? = null,
    @ColumnInfo(name = "username") val username: String? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Instant? = null,
    @ColumnInfo(name = "last_sync_error") val lastSyncError: String? = null,
)

/**
 * A task list. [accountId] is nullable: `NULL` is a device-only list, and
 * attaching one to an account later is a plain `UPDATE` rather than a data
 * migration.
 *
 * Deleting an account detaches its lists (`SET NULL`) instead of deleting them,
 * for the same reason [TaskEntity.parentId] does — removing an account is not
 * an instruction to destroy the tasks it held.
 */
@Entity(
    tableName = "task_lists",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["account_id"])],
)
data class TaskListEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** ARGB. */
    @ColumnInfo(name = "color") val color: Int,
    @ColumnInfo(name = "account_id") val accountId: Long? = null,
    @ColumnInfo(name = "is_visible", defaultValue = "1") val isVisible: Boolean = true,
    @ColumnInfo(name = "is_synced", defaultValue = "1") val isSynced: Boolean = true,
    /** CalDAV owner display name. */
    @ColumnInfo(name = "owner") val owner: String? = null,
    @ColumnInfo(name = "is_read_only", defaultValue = "0") val isReadOnly: Boolean = false,
    /** User ordering. */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    /** Collection URL, relative to the account root. */
    @ColumnInfo(name = "href") val href: String? = null,
    @ColumnInfo(name = "ctag") val ctag: String? = null,
    /** RFC 6578 sync token, per collection. */
    @ColumnInfo(name = "sync_token") val syncToken: String? = null,
    /**
     * Unused, and kept only because dropping a column costs a migration.
     *
     * ⚠️ It was set by a rename and read by nobody. A collection's name and
     * colour are now written server-first by
     * [de.jeanlucmakiola.agendula.data.sync.RemoteLists], so there is
     * no local edit left waiting to be pushed — and a flag that means "owed to
     * the server" while nothing ever pays it is worse than no flag at all.
     */
    @ColumnInfo(name = "is_dirty", defaultValue = "0") val isDirty: Boolean = false,
)

/**
 * A task. Series masters *and* `RECURRENCE-ID` overrides live in this table; an
 * override is a row with [recurrenceId] set and [masterId] pointing at its
 * master, sharing the master's [uid].
 *
 * [masterId] and [parentId] are different things: [parentId] is task hierarchy
 * (`RELATED-TO;RELTYPE=PARENT`), [masterId] is recurrence. A row can carry both.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        // Deleting a series takes its overrides with it — they would otherwise be
        // unreachable rows that still sync.
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["master_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        // Deleting a parent promotes its subtasks to top level rather than
        // destroying work the user did not ask to lose.
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["list_id", "is_deleted"]),
        Index(value = ["parent_id"]),
        Index(value = ["master_id", "recurrence_id"]),
        Index(value = ["is_dirty"]),
        // An override shares its master's UID, so (list_id, uid) alone would
        // reject the very rows recurrence depends on. With recurrence_id NULL on
        // the master and set on each override this reads as: one master and at
        // most one override per occurrence, per UID, per list. Note SQLite treats
        // NULLs as distinct in a unique index, so the master half is a statement
        // of intent, not an enforced constraint.
        Index(value = ["list_id", "uid", "recurrence_id"], unique = true),
    ],
)
data class TaskEntity(
    // identity
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "list_id") val listId: Long,
    /** RFC 4122 UUID, minted at creation in every mode, synced or not. */
    @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "href") val href: String? = null,
    @ColumnInfo(name = "etag") val etag: String? = null,

    // content
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "location") val location: String? = null,
    @ColumnInfo(name = "url") val url: String? = null,
    /** ARGB override for the list colour. */
    @ColumnInfo(name = "color") val color: Int? = null,
    /** RFC 5545 `CATEGORIES` — the tags — in the spelling each task carries them. */
    @ColumnInfo(name = "categories", defaultValue = "")
    @field:TypeConverters(CategoriesConverter::class)
    val categories: List<String> = emptyList(),

    // state
    @ColumnInfo(name = "status", defaultValue = "0") val status: TaskStatus = TaskStatus.NEEDS_ACTION,
    @ColumnInfo(name = "percent_complete") val percentComplete: Int? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Instant? = null,
    /**
     * Raw iCalendar `PRIORITY`: 0 none, 1 highest, 9 lowest. Stored unbucketed —
     * [de.jeanlucmakiola.agendula.domain.Priority] folds 1–4 into HIGH, so
     * converting on the way *in* would rewrite a server's `PRIORITY:3` as `1` and
     * lose it on the next round-trip. The bucketing belongs to the mapper, which
     * is where the UI needs it.
     */
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = PRIORITY_NONE,
    /** RFC 5545 `CLASS`: 0 public, 1 private, 2 confidential. */
    @ColumnInfo(name = "classification") val classification: Int? = null,

    // time
    @ColumnInfo(name = "dtstart") val dtstart: Instant? = null,
    @ColumnInfo(name = "due") val due: Instant? = null,
    /** RFC 5545 `DURATION`, verbatim. Mutually exclusive with [due]. */
    @ColumnInfo(name = "duration") val duration: String? = null,
    @ColumnInfo(name = "is_all_day", defaultValue = "0") val isAllDay: Boolean = false,
    @ColumnInfo(name = "timezone") val timezone: String? = null,

    // recurrence
    @ColumnInfo(name = "rrule") val rrule: String? = null,
    @ColumnInfo(name = "rdate") val rdate: String? = null,
    @ColumnInfo(name = "exdate") val exdate: String? = null,
    /** This row's `RECURRENCE-ID` anchor; `NULL` on a master. */
    @ColumnInfo(name = "recurrence_id") val recurrenceId: Instant? = null,
    /** The series this row overrides; `NULL` on a master. */
    @ColumnInfo(name = "master_id") val masterId: Long? = null,

    // hierarchy
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,

    // audit
    @ColumnInfo(name = "created_at") val createdAt: Instant? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Instant? = null,
    @ColumnInfo(name = "sequence", defaultValue = "0") val sequence: Int = 0,

    // sync
    @ColumnInfo(name = "is_dirty", defaultValue = "0") val isDirty: Boolean = false,
    /** Tombstone: deleted locally, still owed to a server. */
    @ColumnInfo(name = "is_deleted", defaultValue = "0") val isDeleted: Boolean = false,
    /**
     * Raw unfolded iCalendar lines of every property we do not model, re-emitted
     * verbatim on write so a round-trip cannot silently lose a field.
     */
    @ColumnInfo(name = "unknown_properties") val unknownProperties: String? = null,
)

/** What [TaskAlarmEntity.minutesBefore] counts back from. */
enum class AlarmReference { DUE, START }

/** A reminder lead on a task. Positive [minutesBefore] is *before* [reference]. */
@Entity(
    tableName = "task_alarms",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["task_id"])],
)
data class TaskAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "minutes_before") val minutesBefore: Int,
    @ColumnInfo(name = "reference", defaultValue = "DUE") val reference: AlarmReference = AlarmReference.DUE,
    @ColumnInfo(name = "message") val message: String? = null,
)
