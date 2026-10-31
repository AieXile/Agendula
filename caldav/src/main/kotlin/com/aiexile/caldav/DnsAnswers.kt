package com.aiexile.caldav

import org.xbill.DNS.Message
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type

/**
 * Reads SRV and TXT records out of a raw DNS answer.
 *
 * The lookup itself is the platform's job — on Android the system resolver,
 * which follows the current network and honours Private DNS — so this only
 * parses the wire format it hands back. A malformed answer reads as no records.
 */
object DnsAnswers {

    const val TYPE_SRV = Type.SRV
    const val TYPE_TXT = Type.TXT

    fun srv(answer: ByteArray): List<SrvRecord> = records<SRVRecord>(answer)
        .map { SrvRecord(it.priority, it.weight, it.port, it.target.toString(true)) }

    fun txt(answer: ByteArray): List<String> = records<TXTRecord>(answer).flatMap { it.strings }

    private inline fun <reified T> records(answer: ByteArray): List<T> = runCatching {
        Message(answer).getSection(Section.ANSWER).filterIsInstance<T>()
    }.getOrDefault(emptyList())
}
