package de.jeanlucmakiola.caldav

import org.xbill.DNS.Lookup
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type

/**
 * SRV/TXT lookups over dnsjava.
 *
 * Android exposes no usable SRV API — `DnsResolver` arrived in API 29 but is
 * callback-only and does not help with the `TXT path=` half — and JNDI's DNS
 * provider does not exist on Android at all. dnsjava is what DAVx5 uses.
 *
 * A lookup that fails returns an empty list rather than throwing: DNS being
 * unavailable means "no SRV record", which is an ordinary and common answer, and
 * the well-known ladder still has rungs left.
 */
class DnsJavaResolver : DnsResolver {

    override fun srv(name: String): List<SrvRecord> = runCatching {
        Lookup(name, Type.SRV).run()
            .orEmpty()
            .filterIsInstance<SRVRecord>()
            .map { SrvRecord(it.priority, it.weight, it.port, it.target.toString(true)) }
    }.getOrDefault(emptyList())

    override fun txt(name: String): List<String> = runCatching {
        Lookup(name, Type.TXT).run()
            .orEmpty()
            .filterIsInstance<TXTRecord>()
            .flatMap { it.strings }
    }.getOrDefault(emptyList())
}
