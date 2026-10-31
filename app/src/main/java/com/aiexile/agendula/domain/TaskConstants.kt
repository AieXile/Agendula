package com.aiexile.agendula.domain

/**
 * iCalendar `STATUS` values for a `VTODO`, as integers.
 *
 * These live in `domain` rather than being read out of a provider contract: the
 * numbering is Agendula's own storage encoding as much as it is dmfs's, and the
 * domain layer must not depend on the data layer to map its own enums.
 */
object ICalStatus {
    const val NEEDS_ACTION = 0
    const val IN_PROCESS = 1
    const val COMPLETED = 2
    const val CANCELLED = 3
}

/** Priority 0 means "no priority"; 1 is highest, 9 lowest (RFC 5545 §3.8.1.9). */
const val PRIORITY_NONE = 0

/** How a device-only list identifies its (non-existent) account. */
object LocalAccount {
    /** Shown as the section header above device-only lists. */
    const val NAME = "Local"

    /** What Agendula's own store reports for a list with no account. */
    const val TYPE = "local"

    /** What a dmfs-derived provider reports in External mode. */
    const val DMFS_TYPE = "org.dmfs.account.LOCAL"
}
