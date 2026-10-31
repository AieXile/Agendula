package com.aiexile.agendula.domain.ical

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ICalSerializerTest {

    @Test
    fun `a line is folded at 75 octets, not 75 characters`() {
        // Each 'ä' is two octets, so 40 of them is 80 — over the limit at 40
        // characters, which a character-counting folder would let through.
        val property = ICalProperty("SUMMARY", value = "ä".repeat(40))
        val folded = ICalSerializer.fold(ICalSerializer.render(property))

        assertThat(folded).contains("\r\n ")
        folded.split("\r\n").forEach {
            assertThat(it.toByteArray(Charsets.UTF_8).size).isAtMost(75)
        }
    }

    @Test
    fun `folding never splits a multi-byte character`() {
        val value = "🌱".repeat(30)
        val folded = ICalSerializer.fold("SUMMARY:$value")
        val unfolded = ICalParser.unfold(folded + "\r\n").single()
        assertThat(unfolded).isEqualTo("SUMMARY:$value")
    }

    @Test
    fun `a parameter value is quoted only when the grammar forces it`() {
        assertThat(ICalSerializer.render(ICalProperty("X", listOf(ICalParam("P", "plain")), "v")))
            .isEqualTo("X;P=plain:v")
        assertThat(ICalSerializer.render(ICalProperty("X", listOf(ICalParam("P", "a:b")), "v")))
            .isEqualTo("""X;P="a:b":v""")
        assertThat(ICalSerializer.render(ICalProperty("X", listOf(ICalParam("P", "a,b")), "v")))
            .isEqualTo("""X;P="a,b":v""")
    }

    @Test
    fun `an embedded double quote survives when the value need not be quoted`() {
        // The parser only unquotes a matched leading/trailing pair, so stripping
        // unconditionally would lose two characters from a value it never quoted.
        assertThat(ICalSerializer.render(ICalProperty("X", listOf(ICalParam("P", """a"b""")), "v")))
            .isEqualTo("""X;P=a"b:v""")
    }

    @Test
    fun `an embedded double quote is dropped only when quoting is forced`() {
        assertThat(ICalSerializer.render(ICalProperty("X", listOf(ICalParam("P", """a"b,c""")), "v")))
            .isEqualTo("""X;P="ab,c":v""")
    }

    @Test
    fun `components round-trip through serialise and parse`() {
        val original = ICalComponent(
            name = "VTODO",
            properties = listOf(ICalProperty("UID", value = "x")),
            components = listOf(
                ICalComponent("VALARM", listOf(ICalProperty("ACTION", value = "DISPLAY"))),
            ),
        )
        assertThat(ICalParser.parse(ICalSerializer.serialize(original))).isEqualTo(original)
    }
}
