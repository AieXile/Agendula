/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm.property.push

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.XmlUtils
import at.bitfire.dav4jvm.XmlUtils.propertyName
import org.xmlpull.v1.XmlPullParser

/**
 * `P:transports` — the push transports a resource offers.
 *
 * Only Web Push is modelled; any other transport is skipped. [webPush] is null
 * when the server offers no Web Push at all.
 */
data class PushTransports(
    val webPush: WebPush?,
) : Property {

    /** The `P:web-push` transport, with the server's VAPID key if it has one. */
    data class WebPush(
        /** Uncompressed P-256 public key, base64url encoded. */
        val vapidPublicKey: String?,
    )

    companion object {
        @JvmField
        val NAME = WebDAVPush.Transports
    }

    object Factory : PropertyFactory {

        override fun getName() = NAME

        override fun create(parser: XmlPullParser): PushTransports {
            var webPush: WebPush? = null
            val depth = parser.depth
            var eventType = parser.eventType
            while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
                if (eventType == XmlPullParser.START_TAG && parser.depth == depth + 1 &&
                    parser.propertyName() == WebDAVPush.WebPush
                ) {
                    webPush = WebPush(vapidPublicKey = readVapidKey(parser))
                }
                eventType = parser.next()
            }
            return PushTransports(webPush)
        }

        /** Reads a `web-push` element to its end tag, returning its p256ecdsa key. */
        private fun readVapidKey(parser: XmlPullParser): String? {
            var key: String? = null
            val depth = parser.depth
            var eventType = parser.eventType
            while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
                if (eventType == XmlPullParser.START_TAG && parser.depth == depth + 1 &&
                    parser.propertyName() == WebDAVPush.VapidPublicKey
                ) {
                    val type = parser.getAttributeValue(null, "type")
                    val text = XmlUtils.readText(parser)?.trim()
                    if (type == null || type == "p256ecdsa") key = text?.takeIf { it.isNotEmpty() }
                }
                eventType = parser.next()
            }
            return key
        }
    }
}
