package com.aiexile.agendula.ui.accounts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.caldav.CalDavProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What an account is called on screen, and by what mark.
 *
 * `display_name` is `user@host` — the right identity for the system account and
 * for the sync trigger that keys off it, and far too long for a row title. Both
 * halves are stored separately anyway, so the screen shows the one that
 * identifies the account and demotes the other to the supporting line.
 */
internal data class AccountIdentity(
    val provider: CalDavProvider?,
    /** The name: a hosted service's brand, or the host of a server the user runs. */
    val title: String,
    /** The half [title] left out — the host under a brand, the software under a host. */
    val secondary: String?,
    /** Who, on that server. */
    val user: String?,
)

internal fun AccountEntity.identity(): AccountIdentity {
    val url = principalUrl?.toHttpUrlOrNull()
    val provider = url?.let { CalDavProvider.forPrincipal(it) }
    val host = url?.host?.removePrefix("www.")
    val hosted = provider?.hosted == true
    return AccountIdentity(
        provider = provider,
        // The fallback is the stored name: an account with no principal URL
        // never got past discovery, so there is nothing better to call it.
        title = if (hosted) provider.label else host ?: displayName,
        secondary = if (hosted) host else provider?.label,
        user = username?.takeIf { it.isNotBlank() },
    )
}

/**
 * The provider's logo: its mark, in white, on a disc of its own brand colour —
 * the 40dp leading avatar Calendula gives a synced calendar, in colour, so a
 * list of accounts is scannable by mark rather than by reading hostnames.
 *
 * White-on-brand rather than a brand-tinted glyph on a neutral chip, because it
 * is the shape the marks are actually drawn in and it is the one treatment that
 * needs no second colour for dark mode. Providers with no brand colour of their
 * own take the app's [MaterialTheme] accent.
 *
 * There are three treatments, and which one a provider gets is decided by how
 * that provider actually draws itself:
 *
 * 1. **A badge** — [CalDavProvider.badge] — fills the circle edge to edge in its
 *    own colours. Fastmail's icon *is* a ring, so a disc behind it would be a
 *    ring inside a circle, and knocking it back to white would throw away the
 *    logo's larger half.
 * 2. **A mark** — [CalDavProvider.mark] — is tinted white on the brand's disc,
 *    which is how Nextcloud, Apple and Posteo draw these marks themselves.
 * 3. **A lettermark** for everything else. Material's `AlternateEmail` was doing
 *    duty for six providers at once, so a list meant to be read by mark showed
 *    one glyph six times; the brand's own initial tells them apart and claims
 *    nothing. The case comes from [CalDavProvider.label], which is why iCloud
 *    and mailbox.org keep their lowercase letterforms.
 *
 * A real logo beats a letter; an *approximated* logo beats neither, which is why
 * the rest wait for their own art rather than for a good guess at it — and why
 * every mark here is generated from the vendor's own file, not traced by eye.
 */
@Composable
internal fun ProviderLogo(provider: CalDavProvider?, size: Dp = 40.dp) {
    val badge = provider?.badge
    if (badge != null) {
        // ⚠️ On white, not on nothing. The badge is a ring with a transparent
        // middle, drawn for a white page — dropped straight onto the row it lets
        // the surface through, and in dark mode the navy envelope inside it goes
        // very nearly invisible. White is the background the art is drawn for, so
        // it is the background it gets, in both themes.
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(badge),
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
        return
    }

    val accent = provider?.accent
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent ?: MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (accent != null) Color.White else MaterialTheme.colorScheme.onPrimary
        val mark = provider?.mark
        when {
            mark != null -> Icon(
                painter = painterResource(mark.res),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * mark.fraction),
            )

            // A server we know nothing about has no initial to wear.
            provider == null -> Icon(
                imageVector = Icons.Rounded.CloudSync,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * GLYPH_FRACTION),
            )

            else -> Text(
                text = provider.letter,
                color = tint,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = with(LocalDensity.current) { (size * LETTER_FRACTION).toSp() },
                // The name is on the row beside it. A screen reader announcing a
                // bare "F" before "Fastmail" is noise, not information.
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** A provider whose official icon is a finished badge, colours and all. */
private val CalDavProvider.badge: Int?
    get() = when (this) {
        CalDavProvider.FASTMAIL -> R.drawable.ic_provider_fastmail
        CalDavProvider.MAILBOX_ORG -> R.drawable.ic_provider_mailbox
        else -> null
    }

/**
 * The letter a service is known by.
 *
 * Its own initial, except where the service's actual mark *is* a different
 * letter: Yandex's is a Cyrillic Я, which is something we can set rather than
 * art we would have to trace — the only vector they publish is a 64px raster.
 */
private val CalDavProvider.letter: String
    get() = when (this) {
        CalDavProvider.YANDEX -> "Я"
        else -> label.take(1)
    }

/**
 * A monochrome mark and how much of the disc it is given.
 *
 * The fraction is not one number because the marks are not one shape: a wide
 * mark squared off into the same box reads smaller than a compact one, so it is
 * given more room to land on the same optical weight.
 */
private data class Mark(val res: Int, val fraction: Float)

private val CalDavProvider.mark: Mark?
    get() = when (this) {
        CalDavProvider.NEXTCLOUD -> Mark(R.drawable.ic_provider_nextcloud, WIDE_MARK_FRACTION)
        CalDavProvider.ICLOUD -> Mark(R.drawable.ic_provider_icloud, WIDE_MARK_FRACTION)
        CalDavProvider.POSTEO -> Mark(R.drawable.ic_provider_posteo, GLYPH_FRACTION)
        else -> null
    }

/**
 * The provider's own brand colour, where it publishes one recognisable enough to
 * be worth carrying. Null means "we would be inventing it" — Baïkal, DAViCal and
 * SOGo have no colour anyone would recognise, so they take the theme's accent
 * instead of a made-up one.
 *
 * Each is dark enough to carry a white mark, which is the whole treatment: no
 * dark-mode variant is needed because neither colour in the pair moves.
 */
private val CalDavProvider.accent: Color?
    get() = when (this) {
        CalDavProvider.NEXTCLOUD -> Color(0xFF0082C9)
        CalDavProvider.ICLOUD -> Color(0xFF007AFF)
        CalDavProvider.GOOGLE -> Color(0xFF1A73E8)
        CalDavProvider.FASTMAIL -> Color(0xFF2B6CB0)
        CalDavProvider.MAILBOX_ORG -> Color(0xFF0069B4)
        // Their own, off their app icon — not the darker green that was
        // guessed at before the art arrived.
        CalDavProvider.POSTEO -> Color(0xFFA9D158)
        CalDavProvider.ZOHO -> Color(0xFFE42527)
        CalDavProvider.YANDEX -> Color(0xFFFF2500)
        CalDavProvider.BAIKAL, CalDavProvider.DAVICAL, CalDavProvider.SOGO -> null
    }

/** The mark sits on the disc the way a launcher icon does — a little over half. */
private const val GLYPH_FRACTION = 0.55f

/** A letter reads smaller than a glyph of the same box, so it is given less. */
private const val LETTER_FRACTION = 0.44f

/** A wide mark squared off into the same box has to be given more to match. */
private const val WIDE_MARK_FRACTION = 0.72f
