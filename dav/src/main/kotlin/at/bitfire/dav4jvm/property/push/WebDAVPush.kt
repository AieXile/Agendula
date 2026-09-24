/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm.property.push

import at.bitfire.dav4jvm.Property

/**
 * Element names of the WebDAV-Push draft (https://github.com/bitfireAT/webdav-push).
 *
 * Backported from upstream dav4jvm; see `dav/PROVENANCE.md` change 7.
 */
object WebDAVPush {

    const val NS_WEBDAV_PUSH = "https://bitfire.at/webdav-push"

    val AuthSecret = Property.Name(NS_WEBDAV_PUSH, "auth-secret")
    val ContentEncoding = Property.Name(NS_WEBDAV_PUSH, "content-encoding")
    val ContentUpdate = Property.Name(NS_WEBDAV_PUSH, "content-update")
    val Expires = Property.Name(NS_WEBDAV_PUSH, "expires")
    val PropertyUpdate = Property.Name(NS_WEBDAV_PUSH, "property-update")
    val PushMessage = Property.Name(NS_WEBDAV_PUSH, "push-message")
    val PushRegister = Property.Name(NS_WEBDAV_PUSH, "push-register")
    val PushResource = Property.Name(NS_WEBDAV_PUSH, "push-resource")
    val Subscription = Property.Name(NS_WEBDAV_PUSH, "subscription")
    val SubscriptionPublicKey = Property.Name(NS_WEBDAV_PUSH, "subscription-public-key")
    val Topic = Property.Name(NS_WEBDAV_PUSH, "topic")
    val Transports = Property.Name(NS_WEBDAV_PUSH, "transports")
    val Trigger = Property.Name(NS_WEBDAV_PUSH, "trigger")
    val VapidPublicKey = Property.Name(NS_WEBDAV_PUSH, "vapid-public-key")
    val WebPush = Property.Name(NS_WEBDAV_PUSH, "web-push")
    val WebPushSubscription = Property.Name(NS_WEBDAV_PUSH, "web-push-subscription")
}
