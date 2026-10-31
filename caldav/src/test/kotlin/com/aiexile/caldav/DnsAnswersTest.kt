package com.aiexile.caldav

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Section
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type

class DnsAnswersTest {

    private val name = Name.fromString("_caldavs._tcp.mailbox.org.")

    private fun answer(type: Int, vararg records: Record): ByteArray {
        val message = Message.newQuery(Record.newRecord(name, type, DClass.IN))
        records.forEach { message.addRecord(it, Section.ANSWER) }
        return message.toWire()
    }

    @Test
    fun `reads SRV records`() {
        val bytes = answer(
            Type.SRV,
            SRVRecord(name, DClass.IN, 300, 10, 1, 443, Name.fromString("dav.mailbox.org.")),
        )
        assertThat(DnsAnswers.srv(bytes)).containsExactly(SrvRecord(10, 1, 443, "dav.mailbox.org"))
    }

    @Test
    fun `reads TXT strings`() {
        val bytes = answer(Type.TXT, TXTRecord(name, DClass.IN, 300, "path=/"))
        assertThat(DnsAnswers.txt(bytes)).containsExactly("path=/")
    }

    @Test
    fun `an empty or malformed answer has no records`() {
        assertThat(DnsAnswers.srv(answer(Type.SRV))).isEmpty()
        assertThat(DnsAnswers.srv(byteArrayOf(1, 2, 3))).isEmpty()
    }
}
