/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm.property

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.XmlUtils
import at.bitfire.dav4jvm.XmlUtils.propertyName
import org.xmlpull.v1.XmlPullParser

// see RFC 5397: WebDAV Current Principal Extension

data class CurrentUserPrincipal(
        val href: String?,
        /**
         * RFC 5397 §3: the server answered 200 but the request was **not**
         * authenticated. Upstream parsed only the `<href>` child, so this arrived
         * indistinguishable from a conformant-but-empty element and from a server
         * that simply omits the property — and a rejected credential then looks
         * like a successful discovery that found nothing. Local addition; see
         * dav/PROVENANCE.md change 6.
         */
        val unauthenticated: Boolean = false
): Property {

    companion object {
        @JvmField
        val NAME = Property.Name(XmlUtils.NS_WEBDAV, "current-user-principal")

        val UNAUTHENTICATED = Property.Name(XmlUtils.NS_WEBDAV, "unauthenticated")
    }


    object Factory: PropertyFactory {

        override fun getName() = NAME

        override fun create(parser: XmlPullParser): CurrentUserPrincipal {
            // <!ELEMENT current-user-principal (unauthenticated | href)>
            // One pass over both children: XmlUtils.processTag consumes to the end
            // tag, so it cannot be called twice on the same element.
            var href: String? = null
            var unauthenticated = false

            val depth = parser.depth
            var eventType = parser.eventType
            while (!((eventType == XmlPullParser.END_TAG || eventType == XmlPullParser.END_DOCUMENT) &&
                        parser.depth == depth)) {
                if (eventType == XmlPullParser.START_TAG && parser.depth == depth + 1)
                    when (parser.propertyName()) {
                        DavResource.HREF -> href = XmlUtils.readText(parser)
                        UNAUTHENTICATED -> unauthenticated = true
                    }
                eventType = parser.next()
            }
            return CurrentUserPrincipal(href, unauthenticated)
        }

    }

}
