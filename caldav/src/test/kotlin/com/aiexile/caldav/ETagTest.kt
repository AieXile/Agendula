package com.aiexile.caldav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ETagTest {

    @Test fun `strong tag is unquoted and usable`() {
        val tag = ETag.parse("\"abc123\"")
        assertThat(tag).isEqualTo(ETag("abc123", weak = false))
        assertThat(tag!!.usable).isTrue()
    }

    @Test fun `weak tag keeps its value but is never usable`() {
        val tag = ETag.parse("W/\"abc123\"")
        assertThat(tag).isEqualTo(ETag("abc123", weak = true))
        // RFC 9110 §13.1.1 — a weak tag cannot be used with If-Match, so anything
        // that treats this as a validator writes unconditionally without saying so.
        assertThat(tag!!.usable).isFalse()
    }

    @Test fun `unquoted tag is accepted`() {
        assertThat(ETag.parse("abc123")).isEqualTo(ETag("abc123", weak = false))
    }

    @Test fun `absent and empty are both null`() {
        assertThat(ETag.parse(null)).isNull()
        assertThat(ETag.parse("")).isNull()
        assertThat(ETag.parse("  ")).isNull()
        assertThat(ETag.parse("\"\"")).isNull()
    }

    @Test fun `a parsed property is not re-parsed`() {
        // GetETag has already stripped the marker and recorded it separately, so
        // running its value back through parse() reports every weak tag as strong.
        val property = at.bitfire.dav4jvm.property.GetETag("W/\"abc\"")
        assertThat(ETag.from(property)).isEqualTo(ETag("abc", weak = true))
        assertThat(ETag.parse(property.eTag)).isEqualTo(ETag("abc", weak = false))
    }

    @Test fun `a value of W is not a weak marker`() {
        // "W/" needs something after it; the guard is length, not prefix alone.
        assertThat(ETag.parse("\"W/\"")).isEqualTo(ETag("W/", weak = false))
    }
}
